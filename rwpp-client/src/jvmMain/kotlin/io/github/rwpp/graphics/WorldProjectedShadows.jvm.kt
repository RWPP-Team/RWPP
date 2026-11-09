/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.gameFramework.render.UniquePaint
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas
import io.github.rwpp.logic.GameVisualSettings
import java.util.*

/** Sprite alpha, not a rectangle, supplies a shared directional ground shadow for buildings too. */
internal object DesktopWorldProjectedShadows {
    /** Horizontal sun travel per unit of height, matching the terrain sun at (-0.55,-0.55,0.82). */
    private const val SUN_HORIZONTAL_PER_HEIGHT = 0.55f / 0.82f

    /** Keeps a thin contact edge visible below sprites that stand flat on the ground. */
    private const val SHADOW_CONTACT_ANCHOR = 1.5f

    /** Footprints contribute a bounded anchor only; a wide base must not push its shadow away. */
    private const val SHADOW_FOOTPRINT_SHARE = 0.08f
    private const val SHADOW_MAX_TRAVEL = 32f

    internal val FRAGMENT_SHADER get() = WorldShadowShaderSources.FRAGMENT_SHADER


    private val coverage = ProjectedShadowCoverage<OrderableUnit>()
    private var shader: ShadowShader? = null
    private var paint: UniquePaint? = null
    private var failed = false
    private val source = Rect()
    private val target = RectF()

    private data class ShadowFrame(val x: Int, val y: Int, val width: Int, val height: Int)

    private val buildingMasks = IdentityHashMap<com.corrodinggames.rts.gameFramework.render.BitmapOrTexture,
            MutableMap<ShadowFrame, com.corrodinggames.rts.gameFramework.render.BitmapOrTexture?>>()
    private var activeMap: Any? = null

    private fun clearBuildingMasks() {
        buildingMasks.values.forEach { frames -> frames.values.filterNotNull().forEach { it.disposeBitmap() } }
        buildingMasks.clear()
    }

    private fun buildingMask(
        renderer: JavaCanvas, texture: com.corrodinggames.rts.gameFramework.render.BitmapOrTexture,
        bitmap: JavaBitmap, frame: Rect
    ): com.corrodinggames.rts.gameFramework.render.BitmapOrTexture? {
        val key = ShadowFrame(frame.left, frame.top, frame.width(), frame.height())
        val frames = buildingMasks.getOrPut(texture) { mutableMapOf() }
        if (frames.containsKey(key)) return frames[key]
        renderer.flushBitmap()
        // Read/calculate/upload once per texture frame, never once per building per game frame.
        val result = runCatching {
            bitmap.ensurePixelData()
            val pixels =
                IntArray(key.width * key.height) { i -> bitmap.getPixel(key.x + i % key.width, key.y + i / key.width) }
            val filled = solidBuildingShadowPixels(pixels, key.width, key.height)
            renderer.createImageImpl(key.width, key.height, true).also { image ->
                image.ensurePixelData()
                for (y in 0 until key.height) for (x in 0 until key.width) image.setPixel(
                    x,
                    y,
                    filled[y * key.width + x]
                )
                image.commitPixelData()
                (image.loadedImage as? JavaBitmap)?.slickImage?.filter = org.newdawn.slick.Image.FILTER_LINEAR
            }
        }.getOrNull()
        frames[key] = result
        return result
    }

    /**
     * Ground displacement of a projected shadow, in world units before the camera zoom. The sun
     * elevation fixes the travel per unit of height, so width no longer separates a building from
     * its base; the footprint contributes a small bounded anchor for flat ground sprites.
     */
    internal fun projectedOffset(radius: Float, height: Float): Pair<Float, Float> =
        worldProjectedOffset(radius, height)

    internal fun shadowSoftness(height: Float): Float = worldShadowSoftness(height)
    fun beginDrawGame() = coverage.beginDrawGame()

    fun endDrawGame() = coverage.endDrawGame()

    fun replacesNative(unit: OrderableUnit): Boolean = coverage.shouldReplace(
        unit,
        WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()
    )

    fun drawVisible() {
        // The usual call is inside drawNativeWorld's try/finally. Begin again here so direct native
        // drawGame callers also get a fresh frame and never inherit last frame's covered units.
        coverage.beginDrawGame()
        if (GameVisualSettings.get()?.drawUnitShadows == false) return
        if (!WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()) {
            clearBuildingMasks()
            if (failed) {
                shader = null; paint = null
            }
            failed = false
            return
        }
        if (failed) return
        val engine = GameEngine.getInstance() ?: return
        val world = nativeWorldFrame(engine) ?: run { clearBuildingMasks(); activeMap = null; return }
        val renderer = engine.graphics as? JavaCanvas ?: return
        if (activeMap !== engine.map) {
            clearBuildingMasks(); activeMap = engine.map
        }
        if (!renderer.shadersEnabled) return
        if (shader?.errorState?.let { it != 0 } == true) {
            failed = true; return
        }
        if (shader == null) {
            val created = ShadowShader()
            shader = created
            paint = UniquePaint().apply {
                // If the native program is rejected, the fallback is still a dark alpha silhouette.
                color = WORLD_PROJECTED_SHADOW_ALPHA shl 24
                setAntiAlias(true)
                setShader(created)
            }
        }
        val program = shader ?: return
        val shadowPaint = paint ?: return
        val objects = (engine as NativeWorldAccess).rwppVisibleObjects()
        val graphics = engine.graphics
        graphics.save()
        try {
            engine.applyZoomTransform()
            // JavaCanvas remaps small images to a shared atlas inside drawImage(). Atlas insertion
            // draws through a second JavaCanvas, which shares JavaCanvas.currentShader globally;
            // changing canvases mid shadow batch can flush earlier units with the wrong GL program.
            // These sprites are small and drawn once in this pass, so bypass atlas mapping here.
            withTextureAtlasTemporarilyDisabled(
                readAtlas = { renderer.textureAtlas },
                writeAtlas = { renderer.textureAtlas = it }
            ) {
                val items = objects.items()
                for (index in objects.indices) {
                    val unit = items[index] as? OrderableUnit ?: continue
                    if (unit.dead || unit.buildProgress < 1f || unit.transportedBy != null || unit.attachedToUnit != null || unit.height < -1f ||
                        !nativeUnitVisible(world, unit) || unit.isVirtualUnit
                    ) continue
                    val texture = unit.bodyImage ?: continue
                    val bitmap = texture.loadedImage as? JavaBitmap ?: continue
                    var image = bitmap.slickImage ?: continue
                    var shadowTexture = texture
                    val frame = unit.getSpriteFrameRect(false)
                    source.set(frame.left, frame.top, frame.right, frame.bottom)
                    if (source.width() <= 0 || source.height() <= 0) continue
                    if (unit.isBuilding) {
                        val solid = buildingMask(renderer, texture, bitmap, source)
                        val solidImage = (solid?.loadedImage as? JavaBitmap)?.slickImage
                        if (solid != null && solidImage != null) {
                            shadowTexture = solid; image = solidImage
                            source.set(0, 0, solid.width, solid.height)
                        }
                    }
                    val bounds = unit.bodyScreenRect
                    val offset = projectedOffset(unit.radius, unit.height)
                    target.set(
                        bounds.left + offset.first, bounds.top + offset.second,
                        bounds.right + offset.first, bounds.bottom + offset.second
                    )
                    val centerX = (target.left + target.right) * 0.5f
                    val centerY = (target.top + target.bottom) * 0.5f
                    val u = image.textureWidth / image.width
                    val v = image.textureHeight / image.height
                    program.getUniform("u_shadowUv")
                        .setShaderValue4(source.left * u, source.top * v, source.right * u, source.bottom * v)
                    program.setFloat2Uniform("u_shadowTexel", u, v)
                    program.setFloatUniform("u_shadowSoft", shadowSoftness(unit.height))
                    graphics.save()
                    try {
                        graphics.scaleAroundPoint(1.04f, if (unit.isBuilding) 1f else 0.90f, centerX, centerY)
                        graphics.rotate(unit.getDrawAngle(false), centerX, centerY)
                        graphics.drawImage(shadowTexture, source, target, shadowPaint)
                    } finally {
                        graphics.restore()
                    }
                    if (program.errorState != 0) {
                        failed = true; break
                    }
                    coverage.mark(unit)
                }
            }
        } finally {
            graphics.restore()
        }
    }

    internal class ShadowShader : PaintGLShader("assets/shaders/post_base.frag") {
        /** Keep native vertex input, but never let the native file watcher replace our fragment. */
        override fun readSources() {
            pinProjectedShadowFragmentAfterNativeReload(::readNativeSources) { fragmentSource = it }
        }

        private fun readNativeSources() = super.readSources()

        override fun prepareDraw(
            paint: android.graphics.Paint?,
            texture: com.corrodinggames.rts.gameFramework.render.BitmapOrTexture?
        ): Boolean {
            super.prepareDraw(paint, texture)
            return values.any { it.dirty }
        }
    }
}

internal actual fun replacesNativeProjectedShadow(unit: OrderableUnit): Boolean =
    DesktopWorldProjectedShadows.replacesNative(unit)
