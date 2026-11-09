/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasDirect
import io.github.rwpp.logic.GameVisualSettings
import com.corrodinggames.rts.game.map.Map as NativeMap

internal actual fun drawNativeWorld(engine: Any, canvas: Any?, frameDelta: Float) {
    if (Build.VERSION.SDK_INT >= 33) AndroidTerrainPost.drawWorld(engine, canvas, frameDelta)
    else invokeNativeWorld(engine, canvas, frameDelta)
}

internal actual fun drawNativeMap(map: NativeMap, frameDelta: Float) {
    if (Build.VERSION.SDK_INT >= 33) AndroidTerrainPost.drawMap(map, frameDelta) else map.drawMap(frameDelta)
}

internal actual fun replacesNativeProjectedShadow(unit: OrderableUnit): Boolean =
    Build.VERSION.SDK_INT >= 33 && AndroidTerrainPost.replaces(unit)

/** Resource adapters only: all five programs and sprite/material geometry come from commonMain. */
@RequiresApi(33)
private object AndroidTerrainPost {
    private var node: RenderNode? = null
    private var worldNode: RenderNode? = null
    private var shader: RuntimeShader? = null
    private var post: RuntimeShader? = null
    private var background: RuntimeShader? = null
    private var mask: Bitmap? = null
    private var maskShader: BitmapShader? = null
    private var maskVersion = -1L
    private var spriteMask: Bitmap? = null
    private var spriteCanvas: Canvas? = null
    private var spriteShader: BitmapShader? = null
    private val signature = WorldUnitMaterialFrameSignature()
    private val previousSignature = WorldUnitMaterialFrameSignature()
    private val matrixValues = FloatArray(9)
    private var signatureValid = false
    private var spriteCameraX = 0f
    private var spriteCameraY = 0f
    private var spriteCameraValid = false
    private val grid = WorldSceneMaterialGrid()
    private val coverage = ProjectedShadowCoverage<OrderableUnit>()
    private var active = false
    private var lastMap: NativeMap? = null
    private var lastMaskUpdate = 0L
    private var retryAfter = 0L
    private var time = 0f
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadows = object : LinkedHashMap<String, Pair<RuntimeShader, Bitmap>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<RuntimeShader, Bitmap>>?) =
            size > 256
    }
    private val solids = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 128
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = WORLD_PROJECTED_SHADOW_ALPHA }
    private val target = RectF()
    private val source = Rect()
    fun replaces(unit: OrderableUnit) = coverage.shouldReplace(unit, active)

    private fun sizes(program: RuntimeShader, name: String, w: Int, h: Int) =
        program.setFloatUniform("rwppSize_$name", w.toFloat(), h.toFloat())

    private fun world(program: RuntimeShader, engine: GameEngine) {
        val scale = (engine.zoomScale * engine.renderScale).coerceAtLeast(0.001f)
        program.setFloatUniform("rwppWorldOrigin", engine.cameraRenderX, engine.cameraRenderY)
        program.setFloatUniform("rwppWorldPerPixel", 1f / scale, 1f / scale)
    }

    private fun nearest(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_NEAREST
    }

    private fun updateMask(engine: GameEngine, map: NativeMap): Boolean {
        val now = System.nanoTime()
        if (lastMap !== map || mask == null || !grid.coversView(engine, map) || now - lastMaskUpdate > 250_000_000L) {
            if (!grid.update(engine, map)) return false
            lastMap = map; lastMaskUpdate = now
        }
        if (mask?.width != grid.width || mask?.height != grid.height) {
            mask = createBitmap(grid.width, grid.height)
            maskShader = nearest(mask!!); maskVersion = -1
        }
        if (maskVersion != grid.version) {
            mask!!.setPixels(grid.pixels, 0, grid.width, 0, 0, grid.width, grid.height)
            maskShader = nearest(mask!!); maskVersion = grid.version
        }
        return true
    }

    fun drawWorld(owner: Any, nativeCanvas: Any?, delta: Float) {
        val engine = GameEngine.getInstance()
        val wrapper = engine.graphics.currentCanvas as? CanvasDirect
        val canvas = wrapper?.canvas
        coverage.beginDrawGame()
        if (!WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()) {
            release(); invokeNativeWorld(owner, nativeCanvas, delta); return
        }
        if (canvas == null || !canvas.isHardwareAccelerated || canvas.width <= 0 || canvas.height <= 0 || System.nanoTime() < retryAfter) {
            invokeNativeWorld(owner, nativeCanvas, delta); return
        }
        val map = engine.map ?: run { invokeNativeWorld(owner, nativeCanvas, delta); return }
        try {
            if (!updateMask(engine, map)) {
                invokeNativeWorld(owner, nativeCanvas, delta); return
            }
            shader = shader ?: RuntimeShader(WorldCanvasShaderSources.TERRAIN)
            post = post ?: RuntimeShader(WorldCanvasShaderSources.POST)
            background = background ?: RuntimeShader(WorldCanvasShaderSources.BACKGROUND)
        } catch (failure: Exception) {
            fail(failure); invokeNativeWorld(owner, nativeCanvas, delta); return
        }
        time = advanceWorldLightingTime(time, delta)
        val frame = worldNode ?: RenderNode("RWPP complete world lighting").also { worldNode = it }
        frame.setPosition(0, 0, canvas.width, canvas.height)
        val recording = frame.beginRecording(canvas.width, canvas.height)
        val matrix = Matrix(canvas.matrix)
        var complete = false
        try {
            recording.setMatrix(matrix); wrapper.canvas = recording; active = true
            invokeNativeWorld(owner, nativeCanvas, delta)
            complete = true
        } catch (failure: Exception) {
            fail(failure)
        } finally {
            active = false; coverage.endDrawGame(); wrapper.canvas = canvas; frame.endRecording()
        }
        if (!complete) {
            invokeNativeWorld(owner, nativeCanvas, delta); return
        }
        try {
            updateSprites(engine, canvas.width, canvas.height, matrix)
            bindPost(engine, map, canvas.width, canvas.height)
            frame.setRenderEffect(RenderEffect.createRuntimeShaderEffect(post!!, "u_texture"))
        } catch (failure: Exception) {
            fail(failure); frame.setRenderEffect(null)
        }
        val saved = canvas.save()
        try {
            canvas.setMatrix(null); canvas.drawRenderNode(frame)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    fun drawMap(map: NativeMap, delta: Float) {
        val engine = GameEngine.getInstance()
        val wrapper = engine.graphics.currentCanvas as? CanvasDirect
        val canvas = wrapper?.canvas
        if (!active || canvas == null) {
            map.drawMap(delta); return
        }
        val program = shader!!
        world(program, engine); sizes(program, "u_texture", canvas.width, canvas.height)
        sizes(program, "u_terrainMask", grid.width, grid.height)
        program.setInputBuffer("u_terrainMask", maskShader!!)
        program.setFloatUniform(
            "u_maskWorldOrigin",
            grid.originX * map.tileWidth.toFloat(),
            grid.originY * map.tileHeight.toFloat()
        )
        program.setFloatUniform(
            "u_maskWorldScale",
            grid.resolution.toFloat() / (grid.width * map.tileWidth),
            grid.resolution.toFloat() / (grid.height * map.tileHeight)
        )
        program.setFloatUniform("u_maskExtent", 1f, 1f)
        program.setFloatUniform("u_maskTexel", 1f / grid.width, 1f / grid.height)
        program.setFloatUniform("u_sourceTexel", 1f / canvas.width, 1f / canvas.height)
        program.setFloatUniform("u_cacheScale", engine.zoomScale * engine.renderScale)
        program.setFloatUniform("u_time", time)
        program.setFloatUniform("u_lightStrength", WorldGraphicsEnhancements.worldLightingStrength())
        program.setFloatUniform("u_waveStrength", WorldGraphicsEnhancements.waterWaveStrength())
        program.setFloatUniform("u_wetStrength", WorldGraphicsEnhancements.wetGroundStrength())
        program.setFloatUniform(
            "u_terrainHighlights",
            if (WorldGraphicsEnhancements.surfaceHighlightsEnabled()) 1f else 0f
        )
        val terrain = node ?: RenderNode("RWPP shared terrain materials").also { node = it }
        terrain.setPosition(0, 0, canvas.width, canvas.height)
        terrain.setRenderEffect(RenderEffect.createRuntimeShaderEffect(program, "u_texture"))
        val recording = terrain.beginRecording(canvas.width, canvas.height)
        try {
            recording.setMatrix(canvas.matrix); wrapper.canvas = recording; map.drawMap(delta)
        } finally {
            wrapper.canvas = canvas; terrain.endRecording()
        }
        val saved = canvas.save()
        try {
            canvas.setMatrix(null); canvas.drawRenderNode(terrain)
        } finally {
            canvas.restoreToCount(saved)
        }
        drawShadows(engine, canvas)
    }

    private fun bindPost(engine: GameEngine, map: NativeMap, w: Int, h: Int) {
        val base = background!!
        world(base, engine); sizes(base, "u_texture", grid.width, grid.height)
        base.setInputBuffer("u_texture", maskShader!!)
        val zoom = (engine.zoomScale * engine.renderScale).coerceAtLeast(0.001f)
        base.setFloatUniform("u_worldOrigin", engine.cameraRenderX, engine.cameraRenderY)
        base.setFloatUniform("u_viewWorldSize", grid.width / zoom, grid.height / zoom)
        base.setFloatUniform(
            "u_maskWorldOrigin",
            grid.originX * map.tileWidth.toFloat(),
            grid.originY * map.tileHeight.toFloat()
        )
        base.setFloatUniform(
            "u_maskWorldScale",
            grid.resolution.toFloat() / (grid.width * map.tileWidth),
            grid.resolution.toFloat() / (grid.height * map.tileHeight)
        )
        base.setFloatUniform("u_maskExtent", 1f, 1f)
        base.setFloatUniform("u_maskTexel", 1f / grid.width, 1f / grid.height)
        val program = post!!
        world(program, engine); sizes(program, "u_texture", w, h)
        sizes(program, "u_sceneMaterials", w, h)
        sizes(program, "u_spriteCoverage", w, h)
        program.setInputShader("u_sceneMaterials", base)
        program.setInputBuffer("u_spriteCoverage", spriteShader!!)
        program.setFloatUniform("u_sunStrength", WorldGraphicsEnhancements.worldLightingStrength())
        program.setFloatUniform("u_materialPass", 1f)
        program.setFloatUniform("u_separateSpriteCoverage", 1f)
        program.setFloatUniform("u_spriteUvScale", 1f, 1f)
        program.setFloatUniform("u_materialUvScale", 1f, 1f)
        program.setFloatUniform("u_sceneExtent", 1f, 1f)
        program.setFloatUniform("u_worldPerUv", w / zoom, h / zoom)
        program.setFloatUniform(
            "u_postSSR",
            if (WorldGraphicsEnhancements.isScreenSpaceReflectionsEnabled()) 1f else 0f
        )
        program.setFloatUniform(
            "u_postTraceSteps",
            if (WorldGraphicsEnhancements.isScreenSpaceRayTracingEnabled()) WorldGraphicsEnhancements.screenSpaceRayTraceSteps()
                .toFloat() else 2f
        )
        program.setFloatUniform(
            "u_surfaceHighlights",
            if (WorldGraphicsEnhancements.surfaceHighlightsEnabled()) 1f else 0f
        )
        program.setFloatUniform("u_dynamicBloom", if (WorldGraphicsEnhancements.dynamicBloomEnabled()) 1f else 0f)
        program.setFloatUniform("u_wetStrength", WorldGraphicsEnhancements.wetGroundStrength())
    }

    private fun updateSprites(engine: GameEngine, w: Int, h: Int, matrix: Matrix) {
        if (spriteMask?.width != w || spriteMask?.height != h) {
            spriteMask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8); spriteCanvas = Canvas(spriteMask!!)
            signatureValid = false
        }
        val cameraX = engine.cameraRenderX
        val cameraY = engine.cameraRenderY
        val cameraMoved = spriteCameraValid && (cameraX != spriteCameraX || cameraY != spriteCameraY)
        spriteCameraX = cameraX
        spriteCameraY = cameraY
        spriteCameraValid = true
        signature.reset(); signature.put(w); signature.put(h); signature.put(engine.zoomScale)
        matrix.getValues(matrixValues); matrixValues.forEach(signature::put)
        if (!cameraMoved) {
            WorldSceneSprites.masks(engine) { texture, frame, bounds, angle, x, y, scale, _ ->
                appendSpriteSignature(texture, frame, bounds, angle, x, y, scale)
            }
        }
        if (!cameraMoved && signatureValid && signature.matches(previousSignature)) return
        val canvas = spriteCanvas!!
        canvas.setMatrix(null); canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.setMatrix(matrix); canvas.scale(engine.zoomScale, engine.zoomScale)
        WorldSceneSprites.masks(engine) { texture, frame, bounds, angle, x, y, scale, _ ->
            if (cameraMoved) appendSpriteSignature(texture, frame, bounds, angle, x, y, scale)
            val bitmap = texture.bitmap ?: texture.loadedImage?.bitmap
            if (bitmap != null) {
                val saved = canvas.save()
                try {
                    canvas.scale(scale, scale, x, y); canvas.rotate(angle, x, y); canvas.drawBitmap(
                        bitmap,
                        frame,
                        bounds,
                        spritePaint
                    )
                } finally {
                    canvas.restoreToCount(saved)
                }
            }
        }
        spriteShader = nearest(spriteMask!!)
        previousSignature.copyFrom(signature); signatureValid = true
    }

    private fun appendSpriteSignature(
        texture: com.corrodinggames.rts.gameFramework.render.BitmapOrTexture,
        frame: Rect,
        bounds: RectF,
        angle: Float,
        x: Float,
        y: Float,
        scale: Float
    ) {
        signature.putIdentity(texture); signature.put(texture.version)
        signature.putIdentity(texture.loadedImage)
        signature.putIdentity(texture.bitmap ?: texture.loadedImage?.bitmap)
        signature.put(frame.left); signature.put(frame.top); signature.put(frame.right); signature.put(frame.bottom)
        signature.put(bounds.left); signature.put(bounds.top); signature.put(bounds.right); signature.put(bounds.bottom)
        signature.put(angle); signature.put(x); signature.put(y); signature.put(scale)
    }

    private fun drawShadows(engine: GameEngine, canvas: Canvas) {
        if (GameVisualSettings.get()?.drawUnitShadows == false) return
        val saved = canvas.save()
        try {
            canvas.scale(engine.zoomScale, engine.zoomScale)
            WorldSceneSprites.visible(engine) { unit ->
                if (unit.buildProgress < 1f || unit.height < -1f) return@visible
                val texture = unit.bodyImage ?: return@visible
                var bitmap = texture.bitmap ?: texture.loadedImage?.bitmap ?: return@visible
                val frame = unit.getSpriteFrameRect(false)
                source.set(frame.left, frame.top, frame.right, frame.bottom)
                if (source.width() <= 0 || source.height() <= 0) return@visible
                val originalKey =
                    "${texture.id}:${texture.version}:${source.left}:${source.top}:${source.width()}:${source.height()}"
                if (unit.isBuilding) {
                    bitmap = solids.getOrPut(originalKey) {
                        val pixels = IntArray(source.width() * source.height())
                        bitmap.getPixels(
                            pixels,
                            0,
                            source.width(),
                            source.left,
                            source.top,
                            source.width(),
                            source.height()
                        )
                        Bitmap.createBitmap(
                            solidBuildingShadowPixels(pixels, source.width(), source.height()),
                            source.width(),
                            source.height(),
                            Bitmap.Config.ARGB_8888
                        )
                    }
                    source.set(0, 0, bitmap.width, bitmap.height)
                }
                val soft = worldShadowSoftness(unit.height)
                val key = originalKey + ":" + unit.isBuilding + ":" + soft.toRawBits()
                val shaderPair = shadows.getOrPut(key) {
                    val effect = RuntimeShader(WorldCanvasShaderSources.SHADOW)
                    sizes(effect, "u_texture", bitmap.width, bitmap.height)
                    effect.setFloatUniform("rwppWorldOrigin", 0f, 0f); effect.setFloatUniform(
                    "rwppWorldPerPixel",
                    1f,
                    1f
                )
                    effect.setFloatUniform(
                        "u_shadowUv", source.left.toFloat() / bitmap.width, source.top.toFloat() / bitmap.height,
                        source.right.toFloat() / bitmap.width, source.bottom.toFloat() / bitmap.height
                    )
                    effect.setFloatUniform("u_shadowTexel", 1f / bitmap.width, 1f / bitmap.height)
                    effect.setFloatUniform("u_shadowSoft", soft)
                    effect.setInputShader("u_texture", nearest(bitmap))
                    effect to bitmap
                }
                val bounds = unit.bodyScreenRect
                val offset = worldProjectedOffset(unit.radius, unit.height)
                target.set(
                    bounds.left + offset.first,
                    bounds.top + offset.second,
                    bounds.right + offset.first,
                    bounds.bottom + offset.second
                )
                val cx = target.centerX()
                val cy = target.centerY()
                val checkpoint = canvas.save()
                try {
                    canvas.scale(1.04f, if (unit.isBuilding) 1f else 0.90f, cx, cy)
                    canvas.rotate(unit.getDrawAngle(false), cx, cy)
                    canvas.translate(target.left, target.top)
                    canvas.scale(target.width() / source.width(), target.height() / source.height())
                    canvas.translate(-source.left.toFloat(), -source.top.toFloat())
                    shadowPaint.shader = shaderPair.first
                    canvas.drawRect(source, shadowPaint)
                    coverage.mark(unit)
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun fail(failure: Exception) {
        retryAfter = System.nanoTime() + 5_000_000_000L
        io.github.rwpp.api.logging.logger().warn("Android shared world lighting failed", failure)
    }

    private fun release() {
        active = false; coverage.endDrawGame()
        if (node == null && worldNode == null && shader == null && mask == null && spriteMask == null && grid.width == 0 && retryAfter == 0L) return
        node?.discardDisplayList(); worldNode?.discardDisplayList(); node = null; worldNode = null
        shader = null; post = null; background = null; mask = null; maskShader = null; spriteMask = null; spriteShader =
            null; spriteCanvas = null
        signatureValid = false; previousSignature.reset(); signature.reset(); shadows.clear(); solids.clear()
        spriteCameraValid = false
        lastMap = null; lastMaskUpdate = 0; maskVersion = -1; retryAfter = 0; grid.clear()
    }
}
