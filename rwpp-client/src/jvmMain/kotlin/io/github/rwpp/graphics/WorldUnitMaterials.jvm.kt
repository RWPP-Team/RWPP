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
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.gameFramework.render.UniquePaint
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas

/** Native sprite positions include draw offsets and altitude; bodyScreenRect alone is ground-level. */
/** Slick initializes an FBO with fixed-function drawing; flush/unbind the terrain program first. */
internal fun createUnitMaterialCanvas(renderer: JavaCanvas, image: BitmapOrTexture): GraphicsEngine {
    renderer.PENDING_syncCanvasPublic()
    return (renderer as GraphicsEngine).createTextureCanvas(image).also { canvas ->
        (canvas as? JavaCanvas)?.let { it.textureAtlas = null; it.shadersEnabled = true }
    }
}

internal data class WorldUnitMaterialRenderStats(
    val framesConsidered: Long,
    val framesReused: Long,
    val framesDrawn: Long,
    val bodySpritesDrawn: Long,
    val turretSpritesDrawn: Long,
    val signatureCaptureNanos: Long,
    val unitsSampled: Long,
    val turretSlotsSampled: Long
)

/** R=water, G=shore distance, B=sprite exclusion coverage. Sprites receive no synthetic highlights. */
internal object DesktopWorldUnitMaterials {
    internal val VERTEX_SHADER get() = WorldUnitShaderSources.VERTEX_SHADER
    internal val FRAGMENT_SHADER get() = WorldUnitShaderSources.FRAGMENT_SHADER
    internal val BACKGROUND_FRAGMENT_SHADER get() = WorldUnitShaderSources.BACKGROUND_FRAGMENT_SHADER
    private var background: BackgroundShader? = null
    private var backgroundPaint: UniquePaint? = null
    private var ready = false
    private val screen = RectF()

    private var image: BitmapOrTexture? = null
    private var canvas: GraphicsEngine? = null
    private var program: MaterialShader? = null
    private var paint: UniquePaint? = null
    private var failed = false
    private val source = Rect()
    private val bodyBounds = RectF()
    private val frameSignature = WorldUnitMaterialFrameSignature()
    private val cachedFrameSignature = WorldUnitMaterialFrameSignature()
    private var cachedFrameSignatureValid = false
    private var signatureCameraX = 0f
    private var signatureCameraY = 0f
    private var signatureCameraValid = false
    private var framesConsidered = 0L
    private var framesReused = 0L
    private var framesDrawn = 0L
    private var bodySpritesDrawn = 0L
    private var turretSpritesDrawn = 0L
    private var signatureCaptureNanos = 0L
    private var unitsSampled = 0L
    private var turretSlotsSampled = 0L

    fun texture(): BitmapOrTexture? = if (failed || !ready) null else image

    internal fun renderStats() = WorldUnitMaterialRenderStats(
        framesConsidered, framesReused, framesDrawn, bodySpritesDrawn, turretSpritesDrawn,
        signatureCaptureNanos, unitsSampled, turretSlotsSampled
    )

    fun drawVisible(engine: GameEngine) {
        ready = false
        framesConsidered++
        val renderer = engine.graphics as? JavaCanvas ?: run { invalidateCachedFrame(); return }
        if (!WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()) {
            reset(); failed = false; return
        }
        if (failed || !renderer.shadersEnabled) {
            invalidateCachedFrame(); return
        }
        try {
            val width = engine.screenWidth.toInt().coerceAtLeast(1)
            val height = engine.screenHeight.toInt().coerceAtLeast(1)
            if (image?.width != width || image?.height != height) {
                renderer.flushBitmap()
                reset()
                val created = renderer.createImage(width, height, true)
                image = created
                canvas = createUnitMaterialCanvas(renderer, created)
            }
            val target = canvas ?: run { invalidateCachedFrame(); return }
            val terrain = DesktopWorldTerrainMaterials.materialMask() ?: run {
                invalidateCachedFrame(); return
            }
            val cameraX = engine.cameraRenderX
            val cameraY = engine.cameraRenderY
            val cameraMoved = signatureCameraValid &&
                    (cameraX != signatureCameraX || cameraY != signatureCameraY)
            signatureCameraX = cameraX
            signatureCameraY = cameraY
            signatureCameraValid = true
            beginFrameSignature(engine, renderer, terrain, width, height)
            if (!cameraMoved) {
                val signatureStarted = System.nanoTime()
                try {
                    captureVisibleUnitSignatures(engine)
                } finally {
                    signatureCaptureNanos += System.nanoTime() - signatureStarted
                }
            } else {
                // The current camera guarantees a miss, so add unit inputs during the mask pass
                // instead of doing a separate visible-unit traversal before rebuilding it.
                cachedFrameSignatureValid = false
            }
            if (!cameraMoved && cachedFrameSignatureValid && frameSignature.matches(cachedFrameSignature)) {
                // The texture contains only the stable screen-space terrain tags and sprite alpha
                // mask. Reuse those GPU pixels; the post shader still processes this frame's scene.
                ready = true
                framesReused++
                return
            }
            renderer.flushBitmap()
            val shader = program ?: MaterialShader().also { program = it }
            val materialPaint = paint ?: UniquePaint().apply {
                color = -1; setAntiAlias(true); setShader(shader)
            }.also { paint = it }
            val base = background ?: BackgroundShader().also { background = it }
            DesktopWorldTerrainMaterials.configureMaterialBackground(base, engine, width, height)
            val basePaint =
                backgroundPaint ?: UniquePaint().apply { color = -1; setShader(base) }.also { backgroundPaint = it }
            target.drawColor(0)
            screen.set(0f, 0f, width.toFloat(), height.toFloat())
            source.set(0, 0, terrain.width, terrain.height)
            target.drawImage(terrain, source, screen, basePaint)
            target.save()
            try {
                target.scale(engine.zoomScale, engine.zoomScale)
                WorldSceneSprites.masks(
                    engine,
                    inspectUnit = { unit -> if (cameraMoved) appendUnitFrameSignature(engine, unit) }
                ) { texture, frame, bounds, angle, x, y, scale, turret ->
                    if ((texture.loadedImage as? JavaBitmap)?.slickImage != null) {
                        target.save()
                        try {
                            if (scale != 1f) target.scaleAroundPoint(scale, scale, x, y)
                            target.rotate(angle, x, y)
                            target.drawImage(texture, frame, bounds, materialPaint)
                            if (turret) turretSpritesDrawn++ else bodySpritesDrawn++
                        } finally {
                            target.restore()
                        }
                    }
                }
            } finally {
                target.restore(); target.flushBitmap()
            }
            if (shader.errorState != 0 || base.errorState != 0) error("scene material shader rejected")
            ready = true
            cachedFrameSignature.copyFrom(frameSignature)
            cachedFrameSignatureValid = true
            framesDrawn++
        } catch (failure: Exception) {
            failed = true
            invalidateCachedFrame()
            GameEngine.logWarning("RWPP unit material pass disabled: ${failure.message}")
        } finally {
            renderer.makeCurrent()
        }
    }

    /**
     * Capture every input that changes the generated mask. This is a state check only: the cached
     * texture remains a material mask, never a cached copy of the colored world scene.
     */
    private fun beginFrameSignature(
        engine: GameEngine,
        renderer: JavaCanvas,
        terrain: BitmapOrTexture,
        width: Int,
        height: Int
    ) {
        frameSignature.reset()
        frameSignature.putIdentity(renderer)
        frameSignature.putIdentity(image)
        frameSignature.putIdentity(canvas)
        frameSignature.putIdentity(terrain)
        frameSignature.putIdentity(terrain.loadedImage)
        frameSignature.put(width); frameSignature.put(height)
        frameSignature.put(engine.screenWidth); frameSignature.put(engine.screenHeight)
        frameSignature.put(engine.viewWidth); frameSignature.put(engine.viewHeight)
        frameSignature.put(engine.cameraRenderX); frameSignature.put(engine.cameraRenderY)
        frameSignature.put(engine.zoomScale)
    }

    private fun captureVisibleUnitSignatures(engine: GameEngine) {
        WorldSceneSprites.visible(engine) { unit -> appendUnitFrameSignature(engine, unit) }
    }

    private fun appendUnitFrameSignature(engine: GameEngine, unit: OrderableUnit) {
        unitsSampled++
        val sprite = unit.bodyImage ?: return
        val spriteBitmap = sprite.loadedImage as? JavaBitmap ?: return
        val spriteImage = spriteBitmap.slickImage ?: return
        val frame = unit.getSpriteFrameRect(false)
        if (frame.right <= frame.left || frame.bottom <= frame.top) return

        frameSignature.putIdentity(unit)
        frameSignature.putIdentity(sprite)
        frameSignature.putIdentity(spriteBitmap)
        frameSignature.putIdentity(spriteImage)
        frameSignature.put(frame.left); frameSignature.put(frame.top)
        frameSignature.put(frame.right); frameSignature.put(frame.bottom)
        val bounds = unit.bodyScreenRect
        frameSignature.put(bounds.left); frameSignature.put(bounds.top)
        frameSignature.put(bounds.right); frameSignature.put(bounds.bottom)
        val offset = unit.turretDrawOffset
        frameSignature.put(offset.x); frameSignature.put(offset.y)
        frameSignature.put(unit.renderScale)
        frameSignature.put(unit.height)
        frameSignature.put(unit.getDrawAngle(false))
        frameSignature.put(unit.buildProgress)

        val turretCount = unit.turretCount
        frameSignature.put(turretCount)
        turretSlotsSampled += turretCount
        for (index in 0 until turretCount) {
            val turret = unit.getTurretImage(index)
            frameSignature.putIdentity(turret)
            val bitmap = turret?.loadedImage as? JavaBitmap
            val turretImage = bitmap?.slickImage
            frameSignature.putIdentity(bitmap)
            frameSignature.putIdentity(turretImage)
            if (turretImage == null || turret == null) continue

            val point = unit.getTurretPosition3F(index)
            frameSignature.put(point.x); frameSignature.put(point.y); frameSignature.put(point.z)
            frameSignature.put(unit.turrets[index].angle)
            frameSignature.put(unit.getTurretDrawScale(index))
            frameSignature.put(unit.getTurretImageDrawOffsetX(index))
            frameSignature.put(unit.getTurretImageDrawOffsetY(index))
            frameSignature.put(turret.width); frameSignature.put(turret.height)
            frameSignature.put(turret.halfWidthFloat); frameSignature.put(turret.halfHeightFloat)
        }
    }

    private fun invalidateCachedFrame() {
        cachedFrameSignatureValid = false
        cachedFrameSignature.reset()
        frameSignature.reset()
    }

    private fun reset() {
        invalidateCachedFrame()
        signatureCameraValid = false
        canvas?.PENDING_noop_q(); canvas = null
        image?.disposeBitmap(); image = null
    }

    private class BackgroundShader : PaintGLShader("assets/shaders/post_base.frag") {
        override fun readSources() {
            vertexSource = VERTEX_SHADER; fragmentSource = BACKGROUND_FRAGMENT_SHADER
        }

        override fun prepareDraw(paint: android.graphics.Paint?, texture: BitmapOrTexture?): Boolean {
            super.prepareDraw(paint, texture); return values.any { it.dirty }
        }
    }

    private class MaterialShader : PaintGLShader("assets/shaders/post_base.frag") {
        override fun readSources() {
            vertexSource = VERTEX_SHADER; fragmentSource = FRAGMENT_SHADER
        }

        override fun prepareDraw(paint: android.graphics.Paint?, texture: BitmapOrTexture?): Boolean {
            super.prepareDraw(paint, texture); return values.any { it.dirty }
        }
    }
}
