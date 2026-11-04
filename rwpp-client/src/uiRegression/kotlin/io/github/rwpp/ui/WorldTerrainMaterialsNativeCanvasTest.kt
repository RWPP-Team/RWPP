package io.github.rwpp.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.gameFramework.render.UniquePaint
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas
import io.github.rwpp.graphics.*
import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import org.newdawn.slick.Graphics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises mask creation and shader binding through the game's actual JavaCanvas/JavaBitmap path. */
class WorldTerrainMaterialsNativeCanvasTest {
    @Test
    fun javaCanvasImageDataInitializationPreservesMaskPixelsAndShaderSamplesTextureUnitOne() {
        val nativeDir = locateLwjglNativeDirectory()
        Assume.assumeTrue("Steam lwjgl64.dll was not found; offscreen native GL is unavailable", nativeDir != null)

        val previousNativePath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val pbuffer = try {
            Pbuffer(SIZE, SIZE, PixelFormat().withAlphaBits(8).withDepthBits(0), null, null, ContextAttribs(3, 0))
        } catch (failure: Throwable) {
            restoreNativePath(previousNativePath)
            Assume.assumeTrue("LWJGL could not create an OpenGL pbuffer: ${failure.javaClass.simpleName}", false)
            return
        }

        var renderer: JavaCanvas? = null
        var source: JavaBitmap? = null
        var cacheScaleOne: JavaBitmap? = null
        var cacheScaleThree: JavaBitmap? = null
        var rebuiltCacheScaleThree: JavaBitmap? = null
        var splitFoggedSource: JavaBitmap? = null
        var waterSource: JavaBitmap? = null
        var waterMask: JavaBitmap? = null
        var legacyGpuOnlyMask: JavaBitmap? = null
        var encodedProbeMask: JavaBitmap? = null
        var terrainMask: JavaBitmap? = null
        var lavaMask: JavaBitmap? = null
        var splitMaterialMask: JavaBitmap? = null
        var shader: NativeCanvasTerrainShader? = null
        var previousShader: PaintGLShader? = null
        var previousGraphics: Graphics? = null
        var javaCanvasStateCaptured = false
        try {
            try {
                pbuffer.makeCurrent()
            } catch (failure: Throwable) {
                Assume.assumeTrue("LWJGL pbuffer could not become current: ${failure.javaClass.simpleName}", false)
                return
            }
            val version = GL11.glGetString(GL11.GL_VERSION).orEmpty()
            val numbers = Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues
                ?.drop(1)?.mapNotNull(String::toIntOrNull).orEmpty()
            Assume.assumeTrue(
                "OpenGL 3.0 is required by #version 130; pbuffer reports '$version'",
                numbers.size >= 2 && numbers[0] >= 3
            )

            // Keep JavaCanvas's global shader bookkeeping isolated from other headless tests.
            previousShader = JavaCanvas.currentShader
            previousGraphics = JavaCanvas.lastBoundGraphics
            javaCanvasStateCaptured = true
            JavaCanvas.currentShader = null
            JavaCanvas.lastBoundGraphics = null

            renderer = JavaCanvas().apply {
                shadersEnabled = true
                width = SIZE
                height = SIZE
                // This is the same Slick Graphics path used for the desktop framebuffer, attached
                // to the test pbuffer instead of a GameEngine-created display.
                slickGraphics = Graphics(SIZE, SIZE)
            }

            val legacyProbe = terrainMaterialMaskPixel(water = true, coastDistance = 3f, lava = false)
            legacyGpuOnlyMask = createGpuOnlyMask(renderer!!, legacyProbe)
            val encodedProbe = legacyProbe
            encodedProbeMask = createMask(renderer!!, encodedProbe)
            terrainMask = createMask(
                renderer!!,
                terrainMaterialMaskPixel(water = false, coastDistance = 0f, lava = false)
            )
            lavaMask = createMask(
                renderer!!,
                terrainMaterialMaskPixel(water = false, coastDistance = 0f, lava = true)
            )
            source = createSolidImage(renderer!!, WORLD_SIZE, WORLD_SIZE, argb(255, 70, 135, 60))

            val oldOrigin = readTexturePixel(legacyGpuOnlyMask!!, 0, 0)
            val oldCenter = readTexturePixel(legacyGpuOnlyMask!!, MASK_SIZE / 2, MASK_SIZE / 2)
            assertEquals(
                listOf(255, 191, 255, 128), oldOrigin.toList(),
                "legacy JavaCanvas.createImage with bytesPerPixel=0 repeatedly writes the last tag to origin"
            )
            assertEquals(
                listOf(0, 0, 0, 0), oldCenter.toList(),
                "legacy GPU-only image creation loses all other nontransparent mask texels"
            )

            val encodedUploaded = readTexturePixel(encodedProbeMask!!, MASK_SIZE / 2, MASK_SIZE / 2)
            assertEquals(
                (encodedProbe ushr 16) and 0xff,
                encodedUploaded[0],
                "production mask R should survive JavaCanvas upload"
            )
            assertEquals(
                (encodedProbe ushr 8) and 0xff,
                encodedUploaded[1],
                "production mask G should survive JavaCanvas upload"
            )
            assertEquals(
                encodedProbe and 0xff,
                encodedUploaded[2],
                "production cliff marker B should survive JavaCanvas upload"
            )
            assertEquals(128, encodedUploaded[3], "production non-lava mask encoding should use alpha 128")
            assertEquals(
                255,
                encodedUploaded[0],
                "the water coverage channel in the production encoding should remain set"
            )
            assertEquals(255, encodedUploaded[2], "regular production terrain should encode B=255")

            shader = NativeCanvasTerrainShader()
            shader!!.setFloatUniform("u_time", 0.41f)
            shader!!.setFloatUniform("u_cacheScale", 1f)
            shader!!.setFloatUniform("u_lightStrength", 1f)
            shader!!.setFloatUniform("u_waveStrength", 1f)
            shader!!.setFloatUniform("u_wetStrength", 1f)
            shader!!.setFloatUniform("u_terrainHighlights", 1f)
            shader!!.setFloat2Uniform("u_cacheWorldOrigin", 0f, 0f)
            val maskImage = terrainMask!!.slickImage
            val sourceImage = source!!.slickImage
            shader!!.setFloat2Uniform(
                "u_cacheUvWorldScale",
                sourceImage.width / sourceImage.textureWidth, sourceImage.height / sourceImage.textureHeight
            )
            shader!!.setFloat2Uniform(
                "u_maskWorldScale",
                maskImage.textureWidth / WORLD_SIZE,
                maskImage.textureHeight / WORLD_SIZE
            )
            shader!!.setFloat2Uniform("u_maskExtent", maskImage.textureWidth, maskImage.textureHeight)
            shader!!.setFloat2Uniform(
                "u_maskTexel",
                maskImage.textureWidth / MASK_SIZE,
                maskImage.textureHeight / MASK_SIZE
            )
            shader!!.setFloat2Uniform(
                "u_sourceTexel",
                sourceImage.textureWidth / WORLD_SIZE,
                sourceImage.textureHeight / WORLD_SIZE
            )
            shader!!.setTextureUniform("u_terrainMask", terrainMask!!)
            shader!!.getUniform("u_terrainMask").dirty = true

            val paint = UniquePaint().apply {
                color = -1
                setShader(shader!!)
            }
            val src = Rect(0, 0, WORLD_SIZE, WORLD_SIZE)
            val dst = RectF(0f, 0f, WORLD_SIZE.toFloat(), WORLD_SIZE.toFloat())

            clearFramebuffer()
            renderer!!.drawImage(source!!, src, dst, paint)
            renderer!!.flushBitmap()
            val visibleLand = readFramebufferPixel(SIZE / 2, SIZE / 2)
            assertTrue(
                colorDistance(visibleLand, intArrayOf(70, 135, 60, 255)) > 3,
                "the production terrain shader should sample the JavaCanvas mask on Texture1 and shade land; got=${visibleLand.contentToString()}"
            )
            assertTrue(
                visibleLand[1] > visibleLand[0] && visibleLand[1] > visibleLand[2],
                "grass should retain its green channel after the production material pass"
            )

            // Rebind the same sampler through JavaCanvas's dirty-uniform path. Lava must affect
            // this draw, proving the new material mask is read from unit 1 rather than reused.
            shader!!.setTextureUniform("u_terrainMask", lavaMask!!)
            shader!!.getUniform("u_terrainMask").dirty = true
            clearFramebuffer()
            renderer!!.drawImage(source!!, src, dst, paint)
            renderer!!.flushBitmap()
            val lava = readFramebufferPixel(SIZE / 2, SIZE / 2)
            assertTrue(
                colorDistance(lava, intArrayOf(70, 135, 60, 255)) > 3,
                "the second JavaCanvas Texture1 mask must apply its lava material; got=${lava.contentToString()}"
            )

            // Use production's Image-level normalized extents for the world-to-mask mapping.
            // Slick Image.getTextureWidth() is the normalized usable extent; its Texture's
            // getTextureWidth() is the backing GPU pixel width used to calculate a texel step.
            splitMaterialMask = createMask(
                renderer!!,
                terrainMaterialMaskPixel(water = false, coastDistance = 0f, lava = false)
            )
            splitFoggedSource = createSplitFoggedSource(renderer!!)
            val splitImage = splitMaterialMask!!.slickImage
            val splitTexture = splitImage.texture
            assertTrue(
                splitImage.textureWidth > 0f && splitImage.textureWidth <= 1f,
                "Slick Image textureWidth should be a normalized extent, got ${splitImage.textureWidth}"
            )
            assertTrue(
                abs(splitImage.textureWidth * splitTexture.textureWidth - splitImage.width) < 0.01f,
                "Slick Image textureWidth should be logical width / backing GPU width: image=${splitImage.width}, " +
                        "normalized=${splitImage.textureWidth}, gpu=${splitTexture.textureWidth}"
            )
            assertTrue(
                abs(splitImage.textureHeight * splitTexture.textureHeight - splitImage.height) < 0.01f,
                "Slick Image textureHeight should be logical height / backing GPU height"
            )
            assertEquals(
                255, readTexturePixel(splitMaterialMask!!, 1, MASK_SIZE / 2)[2],
                "regular terrain tag should survive the native upload"
            )
            assertEquals(
                255, readTexturePixel(splitMaterialMask!!, MASK_SIZE - 2, MASK_SIZE / 2)[2],
                "terrain mask must not encode fog visibility"
            )

            shader!!.setFloat2Uniform(
                "u_maskWorldScale", splitImage.textureWidth / WORLD_SIZE,
                splitImage.textureHeight / WORLD_SIZE
            )
            shader!!.setFloat2Uniform("u_maskExtent", splitImage.textureWidth, splitImage.textureHeight)
            shader!!.setFloat2Uniform(
                "u_maskTexel", splitImage.textureWidth / splitMaterialMask!!.width,
                splitImage.textureHeight / splitMaterialMask!!.height
            )
            shader!!.setTextureUniform("u_terrainMask", splitMaterialMask!!)
            shader!!.getUniform("u_terrainMask").dirty = true
            clearFramebuffer()
            renderer!!.drawImage(splitFoggedSource!!, src, dst, paint)
            renderer!!.flushBitmap()
            val exposedHalf = readFramebufferPixel(SIZE / 4, SIZE / 2)
            val foggedHalf = readFramebufferPixel(SIZE * 3 / 4, SIZE / 2)
            assertTrue(
                colorDistance(exposedHalf, intArrayOf(70, 135, 60, 255)) > 3,
                "production normalized world-to-mask uniforms should shade exposed terrain; got=${exposedHalf.contentToString()}"
            )
            assertTrue(
                colorDistance(foggedHalf, intArrayOf(42, 70, 42, 255)) > 3,
                "the same material pass should shade fog-tinted terrain instead of culling it; got=${foggedHalf.contentToString()}"
            )

            waterSource = createSolidImage(renderer!!, WORLD_SIZE, WORLD_SIZE, argb(255, 40, 100, 145))
            waterMask = createMask(renderer!!, terrainMaterialMaskPixel(true, 6f, false))
            shader!!.setTextureUniform("u_terrainMask", waterMask!!)
            shader!!.getUniform("u_terrainMask").dirty = true
            fun waterFrame(time: Float): IntArray {
                shader!!.setFloatUniform("u_time", time)
                clearFramebuffer()
                renderer!!.drawImage(waterSource!!, src, dst, paint)
                renderer!!.flushBitmap()
                val bytes = BufferUtils.createByteBuffer(SIZE * SIZE * 4)
                GL11.glFinish()
                GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
                return IntArray(SIZE * SIZE * 4) { bytes.get(it).toInt() and 255 }
            }

            val firstWater = waterFrame(0.41f)
            val sameWater = waterFrame(0.41f)
            val laterWater = waterFrame(2.41f)
            assertTrue(firstWater.contentEquals(sameWater), "native water rendering must be stable at fixed time")
            val movingPixels = (0 until SIZE * SIZE).count { pixel ->
                (0..2).any { channel -> abs(firstWater[pixel * 4 + channel] - laterWater[pixel * 4 + channel]) >= 3 }
            }
            assertTrue(
                movingPixels > SIZE * SIZE / 4,
                "native JavaCanvas water must visibly animate, not just pass through the original: moving=$movingPixels"
            )
            // Exercise JavaCanvas's CPU-side scale/translation rather than a GL matrix. The
            // zoom deliberately differs from cacheScale, and a fresh 3x bitmap represents a
            // cache rebuild. All three draws sample world position (32.5, 32.5), so its
            // production material lighting must stay fixed.
            cacheScaleOne = createGutteredSolidCache(renderer!!, cacheScale = 1, pixel = argb(255, 70, 135, 60))
            cacheScaleThree = createGutteredSolidCache(renderer!!, cacheScale = 3, pixel = argb(255, 70, 135, 60))
            rebuiltCacheScaleThree =
                createGutteredSolidCache(renderer!!, cacheScale = 3, pixel = argb(255, 70, 135, 60))
            val oneCachePixel = renderCacheWorldPoint(
                renderer!!, shader!!, paint, terrainMask!!,
                cacheScaleOne!!, cacheScale = 1f, zoom = 1f, cameraX = 0f, cameraY = 0f
            )
            val zoomedCachePixel = renderCacheWorldPoint(
                renderer!!, shader!!, paint, terrainMask!!,
                cacheScaleThree!!, cacheScale = 3f, zoom = 1.25f, cameraX = 6.5f, cameraY = 6.5f
            )
            val rebuiltCachePixel = renderCacheWorldPoint(
                renderer!!, shader!!, paint, terrainMask!!,
                rebuiltCacheScaleThree!!, cacheScale = 3f, zoom = 1.25f, cameraX = 6.5f, cameraY = 6.5f
            )
            assertTrue(
                colorDistance(oneCachePixel, zoomedCachePixel) <= 2,
                "JavaCanvas CPU zoom must not shift world-anchored lighting when cacheScale changes; " +
                        "scale1=${oneCachePixel.contentToString()} scale3=${zoomedCachePixel.contentToString()}"
            )
            assertTrue(
                colorDistance(zoomedCachePixel, rebuiltCachePixel) <= 2,
                "rebuilding the same cache must preserve production world lighting; " +
                        "before=${zoomedCachePixel.contentToString()} after=${rebuiltCachePixel.contentToString()}"
            )
            verifyNativeSceneMaterialBuffer(renderer!!, source!!)
            verifyNativePostShaderToggles(renderer!!)
            val output = Path.of("build/ui-regression/terrain-native-pipeline.txt")
            Files.createDirectories(output.parent)
            Files.writeString(
                output, "legacy GPU-only centre=${oldCenter.contentToString()}; " +
                        "fixed mask=${encodedUploaded.contentToString()}; visible land=${visibleLand.contentToString()}; " +
                        "lava=${lava.contentToString()}; fogged terrain=${foggedHalf.contentToString()}; animated water pixels=$movingPixels/${SIZE * SIZE}; " +
                        "cache-scale world light=${oneCachePixel.contentToString()}/${zoomedCachePixel.contentToString()}; " +
                        "rebuild=${rebuiltCachePixel.contentToString()}; same-time water identical"
            )
        } finally {
            runCatching { renderer?.flushBitmap() }
            runCatching { renderer?.slickGraphics?.destroy() }
            runCatching { source?.disposeBitmap() }
            runCatching { splitFoggedSource?.disposeBitmap() }
            runCatching { cacheScaleOne?.disposeBitmap() }
            runCatching { cacheScaleThree?.disposeBitmap() }
            runCatching { rebuiltCacheScaleThree?.disposeBitmap() }
            runCatching { waterSource?.disposeBitmap() }
            runCatching { waterMask?.disposeBitmap() }
            runCatching { legacyGpuOnlyMask?.disposeBitmap() }
            runCatching { encodedProbeMask?.disposeBitmap() }
            runCatching { terrainMask?.disposeBitmap() }
            runCatching { lavaMask?.disposeBitmap() }
            runCatching { splitMaterialMask?.disposeBitmap() }
            if (shader != null) {
                if (shader!!.programId != 0) runCatching { GL20.glDeleteProgram(shader!!.programId) }
                if (shader!!.vertexShaderId != 0) runCatching { GL20.glDeleteShader(shader!!.vertexShaderId) }
                if (shader!!.fragmentShaderId != 0) runCatching { GL20.glDeleteShader(shader!!.fragmentShaderId) }
            }
            runCatching { GL20.glUseProgram(0) }
            if (javaCanvasStateCaptured) {
                JavaCanvas.currentShader = previousShader
                JavaCanvas.lastBoundGraphics = previousGraphics
            }
            pbuffer.destroy()
            restoreNativePath(previousNativePath)
        }
    }

    private fun verifyNativeSceneMaterialBuffer(renderer: JavaCanvas, scene: JavaBitmap) {
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "previous terrain frames must not cause GL errors")
        val mask = renderer.createImage(SIZE, SIZE, true) as JavaBitmap
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "scene mask allocation")
        val gpu = createUnitMaterialCanvas(renderer, mask) as JavaCanvas
        checkSlickPbufferBoundary()
        gpu.shadersEnabled = true; gpu.textureAtlas = null
        val sprite = createSolidImage(renderer, SIZE, SIZE, 0)
        val stoneScene = createSolidImage(renderer, SIZE, SIZE, argb(255, 145, 155, 160))
        sprite.ensurePixelData()
        for (y in 16..47) for (x in 16..47) sprite.setPixel(x, y, -1)
        sprite.commitPixelData()
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "scene sprite upload")
        val classifier = NativeSceneShader(DesktopWorldUnitMaterials.FRAGMENT_SHADER)
        val post = NativeSceneShader(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER)
        try {
            renderer.flushBitmap()
            gpu.drawColor(argb(255, 0, 0, 0))
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "scene mask clear")
            val classifyPaint = UniquePaint().apply { color = -1; setShader(classifier) }
            gpu.drawImage(sprite, Rect(0, 0, SIZE, SIZE), RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), classifyPaint)
            gpu.flushBitmap(); renderer.makeCurrent()
            checkSlickPbufferBoundary()
            val unit = readTexturePixel(mask, 32, 32)
            val ground = readTexturePixel(mask, 4, 4)
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "initial material mask must not cause GL errors")
            assertTrue(
                unit[2] > 245 && unit[0] < 5,
                "native GPU material canvas must classify actual sprite alpha into B: ${unit.contentToString()}"
            )
            assertTrue(ground[2] < 5, "transparent sprite exterior must remain ordinary ground")
            val sourceImage = scene.slickImage;
            val maskImage = mask.slickImage
            post.setTextureUniform("u_sceneMaterials", mask)
            post.setFloatUniform("u_materialPass", 1f)
            post.setFloatUniform("u_sunStrength", 1f)
            post.setFloatUniform("u_postSSR", 0f)
            post.setFloatUniform("u_postTraceSteps", 6f)
            post.setFloatUniform("u_dynamicBloom", 0f)
            post.setFloatUniform("u_wetStrength", 1f)
            post.setFloat2Uniform(
                "u_materialUvScale",
                (sourceImage.width / sourceImage.textureWidth) / (maskImage.width / maskImage.textureWidth),
                (sourceImage.height / sourceImage.textureHeight) / (maskImage.height / maskImage.textureHeight)
            )
            post.setFloat2Uniform(
                "u_worldPerUv",
                sourceImage.width / sourceImage.textureWidth,
                sourceImage.height / sourceImage.textureHeight
            )
            post.setFloat2Uniform("u_sceneExtent", sourceImage.textureWidth, sourceImage.textureHeight)
            val paint = UniquePaint().apply { color = -1; setShader(post) }
            fun frame(highlights: Boolean, surface: JavaBitmap = scene): Pair<IntArray, IntArray> {
                post.setFloatUniform("u_surfaceHighlights", if (highlights) 1f else 0f)
                post.getUniform("u_sceneMaterials").dirty = true
                clearFramebuffer()
                renderer.drawImage(
                    surface,
                    Rect(0, 0, SIZE, SIZE),
                    RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()),
                    paint
                )
                renderer.flushBitmap()
                return readFramebufferPixel(32, 32) to readFramebufferPixel(4, 4)
            }

            val off = frame(false);
            val on = frame(true)
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "initial scene post frames must not cause GL errors")
            assertEquals(0, post.errorState, "production scene material shader must run through native JavaCanvas")
            assertTrue(
                colorDistance(off.first, on.first) <= 2,
                "unit/building pixels must not receive ground highlights"
            )
            assertTrue(
                colorDistance(on.first, intArrayOf(70, 135, 60)) <= 2,
                "masked sprites must retain native colours"
            )
            assertTrue(colorDistance(off.second, on.second) <= 2, "unit gloss must not be painted onto dry ground")
            val matteStone = frame(false, stoneScene);
            val litStone = frame(true, stoneScene)
            assertTrue(
                colorDistance(matteStone.second, litStone.second) > 4,
                "pale stone/snow ground must have its own specular response even outside unit silhouettes"
            )
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "ground material frames must not cause GL errors")
            val aircraft = createSolidImage(renderer, SIZE, SIZE, argb(255, 35, 75, 120))
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "aircraft source allocation must not cause GL errors")
            try {
                aircraft.ensurePixelData()
                for (y in 12..27) for (x in 24..39) aircraft.setPixel(x, y, argb(255, 160, 80, 40))
                aircraft.commitPixelData()
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "aircraft source upload must not cause GL errors")
                val lifted = RectF()
                unitMaterialBodyBounds(RectF(16f, 32f, 48f, 64f), 0f, 0f, 28f, lifted)
                // Use the production exclusion shader and the production altitude transform
                // over a water-tagged background; the aircraft must not become glossy water.
                gpu.drawColor(argb(255, 255, 192, 0))
                gpu.drawImage(sprite, Rect(0, 0, SIZE, SIZE), lifted, classifyPaint)
                gpu.flushBitmap(); renderer.makeCurrent()
                checkSlickPbufferBoundary()
                post.setFloatUniform("u_postSSR", 1f)
                post.setFloatUniform("u_dynamicBloom", 1f)
                frame(true, aircraft)
                val aircraftPixel = readFramebufferPixel(32, SIZE - 1 - 20)
                assertTrue(
                    colorDistance(aircraftPixel, intArrayOf(160, 80, 40)) <= 2,
                    "airborne sprite must retain native RGB above its ground anchor: ${aircraftPixel.contentToString()}"
                )
                assertEquals(
                    GL11.GL_NO_ERROR,
                    GL11.glGetError(),
                    "airborne material exclusion must not cause GL errors"
                )
            } finally {
                aircraft.disposeBitmap()
            }
        } finally {
            renderer.flushBitmap(); gpu.PENDING_noop_q(); mask.disposeBitmap(); sprite.disposeBitmap(); stoneScene.disposeBitmap()
            GL20.glUseProgram(0); JavaCanvas.currentShader = null; JavaCanvas.lastBoundGraphics = null
            for (shader in listOf(classifier, post)) {
                if (shader.programId != 0) GL20.glDeleteProgram(shader.programId)
                if (shader.vertexShaderId != 0) GL20.glDeleteShader(shader.vertexShaderId)
                if (shader.fragmentShaderId != 0) GL20.glDeleteShader(shader.fragmentShaderId)
            }
            renderer.makeCurrent()
        }
    }

    private fun checkSlickPbufferBoundary() {
        // Slick FBOGraphics.unbind hardcodes glReadBuffer(GL_BACK). Our single-buffer
        // pbuffer has only GL_FRONT; a debug context identifies this exact ReadBuffer error.
        // Consume that harness-only condition at FBO boundaries, then check actual post draws
        // separately. A normal double-buffered game window does not have this condition.
        val error = GL11.glGetError()
        assertTrue(
            error == GL11.GL_NO_ERROR ||
                    (error == GL11.GL_INVALID_OPERATION && !GL11.glGetBoolean(GL11.GL_DOUBLEBUFFER)),
            "unexpected GL error at Slick offscreen boundary: $error"
        )
    }

    private fun verifyNativePostShaderToggles(renderer: JavaCanvas) {
        val nativeSource = """
            #version 130
            varying vec4 v_color;
            varying vec2 v_texCoords;
            uniform sampler2D u_texture;
            void main() { gl_FragColor=texture2D(u_texture,v_texCoords)*v_color; }
        """.trimIndent()
        val post = NativeSceneShader(nativeSource)
        val paint = UniquePaint().apply { color = -1; setShader(post) }
        val scene = createSolidImage(renderer, SIZE, SIZE, argb(255, 160, 80, 40))
        val src = Rect(0, 0, SIZE, SIZE)
        val dst = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
        val programs = mutableSetOf<Int>();
        val shaders = mutableSetOf<Int>()
        var mask: JavaBitmap? = null
        fun frame(): IntArray {
            clearFramebuffer()
            // Native map/unit drawing switches away from the post shader between frames.
            renderer.drawImage(scene, src, dst, null)
            renderer.drawImage(scene, src, dst, paint)
            renderer.flushBitmap()
            programs += post.programId; shaders += post.vertexShaderId; shaders += post.fragmentShaderId
            return readFramebufferPixel(32, 32)
        }

        fun bindMaterials() {
            post.setFloatUniform("u_sunStrength", 1f)
            post.setFloatUniform("u_materialPass", 1f)
            post.setFloatUniform("u_postSSR", 0f)
            post.setFloatUniform("u_postTraceSteps", 6f)
            post.setFloatUniform("u_surfaceHighlights", 1f)
            post.setFloatUniform("u_dynamicBloom", 1f)
            post.setFloatUniform("u_wetStrength", 1f)
            post.setFloat2Uniform("u_materialUvScale", 1f, 1f)
            post.setFloat2Uniform("u_worldPerUv", SIZE.toFloat(), SIZE.toFloat())
            post.setFloat2Uniform("u_sceneExtent", 1f, 1f)
            post.setTextureUniform("u_sceneMaterials", mask!!)
            post.getUniform("u_sceneMaterials").dirty = true
        }
        try {
            val native = frame()
            var enhanced: IntArray? = null
            repeat(8) { cycle ->
                DesktopWorldPostShaderEnhancements.updateSource(post, true)
                mask = createSolidImage(renderer, SIZE, SIZE, argb(255, 0, 128, 0))
                bindMaterials()
                val current = frame()
                assertEquals(
                    GL11.GL_NO_ERROR,
                    GL11.glGetError(),
                    "post uniforms must belong to the new GL program on cycle $cycle"
                )
                assertEquals(0, post.errorState, "post shader must remain valid after toggling")
                assertTrue(
                    current[0] > current[1] && current[1] > current[2],
                    "world source must retain its red/brown hue instead of showing the green material mask: ${current.contentToString()}"
                )
                if (enhanced == null) enhanced = current else assertTrue(
                    colorDistance(enhanced!!, current) <= 2,
                    "same settings and scene must match after re-enabling: first=${enhanced!!.contentToString()}, cycle $cycle=${current.contentToString()}"
                )
                DesktopWorldPostShaderEnhancements.updateSource(post, false)
                val disabled = frame()
                assertEquals(
                    GL11.GL_NO_ERROR,
                    GL11.glGetError(),
                    "native fallback must not upload custom uniforms on cycle $cycle"
                )
                assertTrue(colorDistance(native, disabled) <= 2, "disabling must restore native pixels on cycle $cycle")
                assertTrue(
                    post.values.none { it.texture === mask },
                    "disabled post shader must release disposed material textures"
                )
                mask!!.disposeBitmap(); mask = null
            }
            Files.writeString(
                Path.of("build/ui-regression/post-toggle-native-pipeline.txt"),
                "8 enable/disable cycles: native pixels restored; enhanced pixels repeat; material textures recreated; GL errors=0"
            )
        } finally {
            renderer.flushBitmap(); mask?.disposeBitmap(); scene.disposeBitmap()
            GL20.glUseProgram(0); JavaCanvas.currentShader = null; JavaCanvas.lastBoundGraphics = null
            programs.filter { it != 0 }.forEach(GL20::glDeleteProgram)
            shaders.filter { it != 0 }.forEach(GL20::glDeleteShader)
            renderer.makeCurrent()
        }
    }

    private class NativeSceneShader(fragment: String) : PaintGLShader() {
        init {
            errorState = 0; vertexSource = DesktopWorldTerrainMaterials.VERTEX_SHADER; fragmentSource = fragment
        }

        override fun prepareDraw(paint: Paint?, texture: BitmapOrTexture?): Boolean {
            super.prepareDraw(paint, texture); return values.any { it.dirty }
        }
    }

    private fun createMask(renderer: JavaCanvas, pixel: Int): JavaBitmap {
        return createSolidImage(renderer, MASK_SIZE, MASK_SIZE, pixel)
    }

    private fun createSplitFoggedSource(renderer: JavaCanvas): JavaBitmap {
        val bitmap = createTerrainMaskImage(renderer, WORLD_SIZE, WORLD_SIZE) as JavaBitmap
        bitmap.ensurePixelData()
        for (y in 0 until WORLD_SIZE) for (x in 0 until WORLD_SIZE) {
            val pixel = if (x < WORLD_SIZE / 2) argb(255, 70, 135, 60) else argb(255, 42, 70, 42)
            bitmap.setPixel(x, y, pixel)
        }
        bitmap.commitPixelData()
        return bitmap
    }

    private fun createSolidImage(renderer: JavaCanvas, width: Int, height: Int, pixel: Int): JavaBitmap {
        val bitmap = createTerrainMaskImage(renderer, width, height) as JavaBitmap
        bitmap.ensurePixelData()
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, pixel)
        bitmap.commitPixelData()
        return bitmap
    }

    private fun createGutteredSolidCache(renderer: JavaCanvas, cacheScale: Int, pixel: Int): JavaBitmap =
        createSolidImage(
            renderer, WORLD_SIZE * cacheScale + GUTTER * 2,
            WORLD_SIZE * cacheScale + GUTTER * 2, pixel
        )

    private fun renderCacheWorldPoint(
        renderer: JavaCanvas,
        shader: NativeCanvasTerrainShader,
        paint: UniquePaint,
        mask: JavaBitmap,
        cache: JavaBitmap,
        cacheScale: Float,
        zoom: Float,
        cameraX: Float,
        cameraY: Float
    ): IntArray {
        val sourceImage = cache.slickImage
        val maskImage = mask.slickImage
        shader.setFloatUniform("u_time", 0.41f)
        shader.setFloatUniform("u_cacheScale", cacheScale)
        shader.setFloatUniform("u_lightStrength", 1f)
        shader.setFloatUniform("u_waveStrength", 1f)
        shader.setFloatUniform("u_wetStrength", 1f)
        shader.setFloatUniform("u_terrainHighlights", 1f)
        shader.setFloat2Uniform("u_cacheWorldOrigin", -GUTTER / cacheScale, -GUTTER / cacheScale)
        shader.setFloat2Uniform(
            "u_cacheUvWorldScale",
            sourceImage.width / (sourceImage.textureWidth * cacheScale),
            sourceImage.height / (sourceImage.textureHeight * cacheScale)
        )
        shader.setFloat2Uniform(
            "u_maskWorldScale", maskImage.textureWidth / WORLD_SIZE,
            maskImage.textureHeight / WORLD_SIZE
        )
        shader.setFloat2Uniform("u_maskExtent", maskImage.textureWidth, maskImage.textureHeight)
        shader.setFloat2Uniform(
            "u_maskTexel", maskImage.textureWidth / mask.width,
            maskImage.textureHeight / mask.height
        )
        shader.setFloat2Uniform(
            "u_sourceTexel", sourceImage.textureWidth / sourceImage.width,
            sourceImage.textureHeight / sourceImage.height
        )
        shader.setTextureUniform("u_terrainMask", mask)
        shader.getUniform("u_terrainMask").dirty = true

        clearFramebuffer()
        renderer.save()
        return try {
            renderer.scale(zoom, zoom)
            renderer.translate(-cameraX, -cameraY)
            val physicalExtent = WORLD_SIZE * cacheScale.toInt()
            val source = Rect(GUTTER, GUTTER, GUTTER + physicalExtent, GUTTER + physicalExtent)
            val target = RectF(0f, 0f, WORLD_SIZE.toFloat(), WORLD_SIZE.toFloat())
            renderer.drawImage(cache, source, target, paint)
            renderer.flushBitmap()
            readFramebufferPixel(SIZE / 2, SIZE / 2)
        } finally {
            renderer.restore()
        }
    }

    /** Recreates the buggy GPU-only JavaCanvas.createImage path without production initialization. */
    private fun createGpuOnlyMask(renderer: JavaCanvas, pixel: Int): JavaBitmap {
        val bitmap = renderer.createImage(MASK_SIZE, MASK_SIZE, true) as JavaBitmap
        bitmap.ensurePixelData()
        for (y in 0 until MASK_SIZE) for (x in 0 until MASK_SIZE) bitmap.setPixel(x, y, pixel)
        bitmap.commitPixelData()
        return bitmap
    }

    private fun readTexturePixel(bitmap: JavaBitmap, x: Int, y: Int): IntArray {
        val image = bitmap.slickImage
        val texture = image.texture
        val buffer = BufferUtils.createByteBuffer(texture.textureWidth * texture.textureHeight * 4)
        val active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.textureID)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            val index = (y * texture.textureWidth + x) * 4
            return IntArray(4) { buffer.get(index + it).toInt() and 0xff }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding)
            GL13.glActiveTexture(active)
        }
    }

    private fun readFramebufferPixel(x: Int, y: Int): IntArray {
        val buffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4)
        GL11.glFinish()
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
        val index = (y * SIZE + x) * 4
        return IntArray(4) { buffer.get(index + it).toInt() and 0xff }
    }

    private fun clearFramebuffer() {
        GL11.glViewport(0, 0, SIZE, SIZE)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, SIZE.toDouble(), SIZE.toDouble(), 0.0, -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
    }

    private fun colorDistance(a: IntArray, b: IntArray): Int =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private class NativeCanvasTerrainShader : PaintGLShader() {
        init {
            errorState = 0
            vertexSource = DesktopWorldTerrainMaterials.VERTEX_SHADER
            fragmentSource = DesktopWorldTerrainMaterials.FRAGMENT_SHADER
        }

        override fun prepareDraw(paint: Paint?, texture: BitmapOrTexture?): Boolean {
            super.prepareDraw(paint, texture)
            return values.any { it.dirty }
        }
    }

    private fun locateLwjglNativeDirectory(): Path? {
        val roots = buildList {
            System.getProperty("rwpp.lwjgl.nativePath")?.let { add(Path.of(it)) }
            System.getenv("RWPP_LWJGL_NATIVE_PATH")?.let { add(Path.of(it)) }
            System.getenv("ProgramFiles(x86)")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("ProgramFiles")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("STEAM_ROOT")?.let { add(Path.of(it)) }
            add(Path.of("D:/Steam")); add(Path.of("D:/steam"))
        }
        for (root in roots.distinct()) {
            listOf(
                root,
                root.resolve("steamapps/common/Rusted Warfare"),
                root.resolve("steamapps/common/rusted_warfare")
            )
                .firstOrNull { Files.isRegularFile(it.resolve("lwjgl64.dll")) }?.let { return it }
            val libraries = root.resolve("steamapps/libraryfolders.vdf")
            if (Files.isRegularFile(libraries)) {
                val contents = runCatching { Files.readString(libraries) }.getOrDefault("")
                Regex("\\\"path\\\"\\s*\\\"([^\\\"]+)\\\"").findAll(contents).forEach { match ->
                    val game = Path.of(match.groupValues[1].replace("\\\\", "/"))
                        .resolve("steamapps/common/Rusted Warfare")
                    if (Files.isRegularFile(game.resolve("lwjgl64.dll"))) return game
                }
            }
        }
        return null
    }

    private fun restoreNativePath(previous: String?) {
        if (previous == null) System.clearProperty("org.lwjgl.librarypath") else System.setProperty(
            "org.lwjgl.librarypath",
            previous
        )
    }

    private companion object {
        const val SIZE = 64
        const val MASK_SIZE = 8
        const val WORLD_SIZE = SIZE
        const val GUTTER = 2
    }
}
