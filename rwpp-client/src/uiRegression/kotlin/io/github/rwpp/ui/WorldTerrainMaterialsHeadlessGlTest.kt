package io.github.rwpp.ui

import io.github.rwpp.graphics.*

import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the production whole-cache terrain material shader over a small, real-artwork shoreline.
 * The scene is assembled from the installed game's 20px terrain and ridge sprites, then the same
 * pixels are drawn as one cache texture and as two cache blocks with copied gutters. This keeps
 * the check independent of a loaded game while exercising the production GLSL and real assets.
 *
 * On machines without the Steam assets/LWJGL native, this reports a JUnit skip. When it runs it
 * writes before/after PNGs to build/ui-regression for visual review.
 */
class WorldTerrainMaterialsHeadlessGlTest {
    @Test
    fun nativeCoastSceneLightsFogTintedTerrainAndMatchesGutteredCacheBlocks() {
        val assetRoot = locateGameAssetRoot()
        Assume.assumeTrue("Rusted Warfare terrain assets were not found", assetRoot != null)
        val nativeDir = locateLwjglNativeDirectory()
        Assume.assumeTrue("Steam lwjgl64.dll was not found; offscreen native GL is unavailable", nativeDir != null)

        val previousNativePath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val pbuffer = try {
            Pbuffer(512, 320, PixelFormat().withAlphaBits(8).withDepthBits(0), null, null, ContextAttribs(3, 0))
        } catch (failure: Throwable) {
            restoreNativePath(previousNativePath)
            Assume.assumeTrue("LWJGL could not create an OpenGL pbuffer: ${failure.javaClass.simpleName}", false)
            return
        }

        var vertexShader = 0
        var fragmentShader = 0
        var diagnosticFragmentShader = 0
        var program = 0
        var diagnosticProgram = 0
        val textures = mutableListOf<Int>()
        try {
            try {
                pbuffer.makeCurrent()
            } catch (failure: Throwable) {
                Assume.assumeTrue("LWJGL pbuffer could not become current: ${failure.javaClass.simpleName}", false)
                return
            }
            val version = GL11.glGetString(GL11.GL_VERSION).orEmpty()
            val numbers =
                Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull).orEmpty()
            Assume.assumeTrue(
                "OpenGL 3.0 is required by #version 130; pbuffer reports '$version'",
                numbers.size >= 2 && numbers[0] >= 3
            )

            vertexShader = compile(GL20.GL_VERTEX_SHADER, DesktopWorldTerrainMaterials.VERTEX_SHADER)
            fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, DesktopWorldTerrainMaterials.FRAGMENT_SHADER)
            program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glLinkProgram(program)
            assertEquals(
                GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS),
                "terrain shader link failed: ${GL20.glGetProgramInfoLog(program, 4096)}"
            )

            diagnosticFragmentShader = compile(GL20.GL_FRAGMENT_SHADER, DIAGNOSTIC_FRAGMENT_SHADER)
            diagnosticProgram = GL20.glCreateProgram()
            GL20.glAttachShader(diagnosticProgram, vertexShader)
            GL20.glAttachShader(diagnosticProgram, diagnosticFragmentShader)
            GL20.glLinkProgram(diagnosticProgram)
            assertEquals(
                GL11.GL_TRUE, GL20.glGetProgrami(diagnosticProgram, GL20.GL_LINK_STATUS),
                "world-coordinate diagnostic shader link failed: ${GL20.glGetProgramInfoLog(diagnosticProgram, 4096)}"
            )

            val scene = buildNativeCoastScene(assetRoot!!)
            val tileWater = buildWaterMask(scene.width / TILE, scene.height / TILE)
            val maskImage = encodeMask(scene)
            val fullSource =
                uploadTexture(makeGutteredSource(scene, 0, scene.width), GL13.GL_TEXTURE0).also { textures += it.id }
            val foggedSource = uploadTexture(
                makeGutteredSource(fogTintedScene(scene), 0, scene.width),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val leftSource = uploadTexture(
                makeGutteredSource(scene, 0, scene.width / 2),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val rightSource = uploadTexture(
                makeGutteredSource(scene, scene.width / 2, scene.width / 2),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val scaleTwoFull = uploadTexture(
                makeGutteredSource(scene, 0, scene.width, cacheScale = 2),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val scaleTwoLeft = uploadTexture(
                makeGutteredSource(scene, 0, scene.width / 2, cacheScale = 2),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val scaleTwoRight = uploadTexture(
                makeGutteredSource(scene, scene.width / 2, scene.width / 2, cacheScale = 2),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val scaleThreeRight = uploadTexture(
                makeGutteredSource(scene, scene.width / 2, scene.width / 2, cacheScale = 3),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val mask = uploadTexture(maskImage, GL13.GL_TEXTURE1).also { textures += it.id }

            GL11.glDisable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_DITHER)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glEnable(GL11.GL_TEXTURE_2D)

            val before = renderFull(
                0,
                fullSource,
                scene.width,
                scene.height,
                mask,
                bufferScale = 1f,
                cameraX = 0f,
                cameraY = 0f,
                viewZoom = 1f,
                time = 0.41f,
                program = program
            )
            val after = renderFull(
                program,
                fullSource,
                scene.width,
                scene.height,
                mask,
                bufferScale = 1f,
                cameraX = 0f,
                cameraY = 0f,
                viewZoom = 1f,
                time = 0.41f,
                program = program
            )

            // Keep visual artifacts even when a later quality assertion catches a regression.
            writeComparison(scene, before, after)
            val output = Path.of("build/ui-regression")
            Files.createDirectories(output)
            ImageIO.write(before, "png", output.resolve("native-terrain-material-before.png").toFile())
            ImageIO.write(after, "png", output.resolve("native-terrain-material-after.png").toFile())

            // The fog-tinted source models the native cache's dark overlay in an unexplored
            // rectangle. Terrain materials must still shade those pixels in the same viewport.
            val fogBefore = renderFull(
                0, foggedSource, scene.width, scene.height, mask,
                bufferScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f, time = 0.41f, program = program
            )
            val fogAfter = renderFull(
                program, foggedSource, scene.width, scene.height, mask,
                bufferScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f, time = 0.41f, program = program
            )
            val fogPixels = pixelDifferenceCount(fogBefore, fogAfter, 35..84, 175..234)
            assertTrue(
                fogPixels > 50,
                "terrain lighting must remain active over fog-tinted terrain instead of being fog-culled; changed=$fogPixels"
            )

            val split = renderSplit(
                program,
                leftSource,
                rightSource,
                scene.width,
                scene.height,
                mask,
                bufferScale = 1f,
                cameraX = 0f,
                cameraY = 0f,
                viewZoom = 1f,
                time = 0.41f
            )
            val maxSplitDelta = maxChannelDelta(after, split)
            assertTrue(
                maxSplitDelta <= 2,
                "full and two guttered cache blocks must render identically; max channel delta=$maxSplitDelta"
            )

            // Exercise camera recovery and the real MapLayerBuffer contract: vertex coordinates
            // carry bufferScale, while the renderer applies viewZoom / bufferScale.
            val movedFull = renderFull(
                program, scaleTwoFull, scene.width, scene.height, mask,
                bufferScale = 2f, cameraX = 12f, cameraY = 8f, viewZoom = 1.1f, time = 0.41f, program = program
            )
            val movedSplit = renderSplit(
                program, scaleTwoLeft, scaleTwoRight, scene.width, scene.height, mask,
                bufferScale = 2f, cameraX = 12f, cameraY = 8f, viewZoom = 1.1f, time = 0.41f
            )
            val movedDelta = maxChannelDelta(movedFull, movedSplit)

            assertTrue(
                movedDelta <= 2,
                "camera/zoom world coordinates must remain continuous across cache blocks; max channel delta=$movedDelta"
            )

            // Read back the production vertex shader's actual world varying, rather than merely
            // asserting that its uniform names exist. The same world pixel is sampled from a
            // scale-1 full cache and a scale-3 right cache with camera zoom deliberately distinct
            // from cacheScale, proving that CPU-scaled vertices do not move the material anchor.
            val worldProbeX = 250.5f
            val worldProbeY = 40.5f
            val scaleOneWorld = renderDiagnosticBlock(
                diagnosticProgram, fullSource, cacheScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f,
                originX = 0f, originY = 0f, width = scene.width.toFloat(), height = scene.height.toFloat(),
                sampleWorldX = worldProbeX, sampleWorldY = worldProbeY
            )
            val scaleThreeCameraX = worldProbeX - worldProbeX / 1.25f
            val scaleThreeCameraY = worldProbeY - worldProbeY / 1.25f
            val scaleThreeWorld = renderDiagnosticBlock(
                diagnosticProgram,
                scaleThreeRight,
                cacheScale = 3f,
                cameraX = scaleThreeCameraX,
                cameraY = scaleThreeCameraY,
                viewZoom = 1.25f,
                originX = (scene.width / 2).toFloat(),
                originY = 0f,
                width = (scene.width / 2).toFloat(),
                height = scene.height.toFloat(),
                sampleWorldX = worldProbeX,
                sampleWorldY = worldProbeY
            )
            assertTrue(
                colorDistance(scaleOneWorld, scaleThreeWorld) <= 2,
                "same world coordinate must survive cache scale, split origin, camera translation and zoom; " +
                        "scale1=${scaleOneWorld.contentToString()} scale3=${scaleThreeWorld.contentToString()}"
            )
            assertTrue(
                abs(scaleOneWorld[0] - (worldProbeX / 512f * 255f).toInt()) <= 1 &&
                        abs(scaleOneWorld[1] - (worldProbeY / 512f * 255f).toInt()) <= 1,
                "diagnostic readback must encode the expected world position: ${scaleOneWorld.contentToString()}"
            )

            // A rebuilt 3x cache of the same solid material must produce the same time-locked
            // production lighting at a fixed world point, even with a 1.25x camera zoom.
            val flatScene = BufferedImage(scene.width, scene.height, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                try {
                    g.color = Color(70, 135, 60, 255); g.fillRect(0, 0, width, height)
                } finally {
                    g.dispose()
                }
            }
            val flatScaleOne = uploadTexture(
                makeGutteredSource(flatScene, 0, flatScene.width),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val flatScaleThree = uploadTexture(
                makeGutteredSource(flatScene, 0, flatScene.width, cacheScale = 3),
                GL13.GL_TEXTURE0
            ).also { textures += it.id }
            val lightingProbeX = 50.5f
            val lightingProbeY = 40.5f
            val baseLighting = renderFull(
                program, flatScaleOne, scene.width, scene.height, mask,
                bufferScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f, time = 0.41f, program = program
            )
            val zoomedLighting = renderFull(
                program, flatScaleThree, scene.width, scene.height, mask,
                bufferScale = 3f,
                cameraX = lightingProbeX - lightingProbeX / 1.25f,
                cameraY = lightingProbeY - lightingProbeY / 1.25f,
                viewZoom = 1.25f, time = 0.41f, program = program
            )
            assertTrue(
                colorDistance(rgbAt(baseLighting, 50, 40), rgbAt(zoomedLighting, 50, 40)) <= 2,
                "same-time terrain lighting must stay anchored after a cache rebuild and zoom change; " +
                        "scale1=${rgbAt(baseLighting, 50, 40).contentToString()} " +
                        "scale3=${rgbAt(zoomedLighting, 50, 40).contentToString()}"
            )

            val nextFrame = renderFull(
                program, fullSource, scene.width, scene.height, mask,
                bufferScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f, time = 0.41f + 1f / 60f, program = program
            )
            assertTrue(
                maxChannelDelta(after, nextFrame) <= 6,
                "water reflections must move smoothly without frame flashes"
            )
            val strongLight = renderFull(
                program,
                fullSource,
                scene.width,
                scene.height,
                mask,
                bufferScale = 1f,
                cameraX = 0f,
                cameraY = 0f,
                viewZoom = 1f,
                time = 0.41f,
                program = program,
                strength = 3f
            )
            assertTrue(
                pixelDifferenceCount(after, strongLight, 18..305, 24..235) > 300,
                "the graphics intensity setting must affect the actual production shader"
            )
            val calmA = renderFull(
                program,
                fullSource,
                scene.width,
                scene.height,
                mask,
                1f,
                0f,
                0f,
                1f,
                0.41f,
                program,
                waveStrength = 0f
            )
            val calmB = renderFull(
                program,
                fullSource,
                scene.width,
                scene.height,
                mask,
                1f,
                0f,
                0f,
                1f,
                4.41f,
                program,
                waveStrength = 0f
            )
            assertEquals(
                0,
                pixelDifferenceCount(calmA, calmB, 205..305, 65..190),
                "the wave-strength setting must stop waves at zero"
            )
            assertTrue(
                pixelDifferenceCount(after, calmA, 205..305, 65..190) > 1000,
                "enabled waves must visibly differ from still water"
            )
            val later = renderFull(
                program, fullSource, scene.width, scene.height, mask,
                bufferScale = 1f, cameraX = 0f, cameraY = 0f, viewZoom = 1f, time = 4.41f, program = program
            )
            assertEquals(
                0, pixelDifferenceCount(after, later, 18..158, 24..150),
                "matte land must not acquire animated water/noise/radial light patterns"
            )
            assertTrue(
                pixelDifferenceCount(after, later, 205..305, 65..190) > 100,
                "the production water normal must animate visible reflections"
            )
            val visibleLandChanges = pixelDifferenceCount(before, after, 18..158, 24..235)
            assertTrue(
                visibleLandChanges > 300,
                "the production shader must visibly add material detail to exposed grass/rock; changed=$visibleLandChanges"
            )
            assertNoNewCyanFringe(before, after)
            verifyTerrainRelief(program, scene.width, scene.height)
            writeReferenceLightingScene(assetRoot, program, vertexShader)
            val gif = ImageIO.getImageWritersByFormatName("gif").next()
            try {
                ImageIO.createImageOutputStream(Path.of("build/ui-regression/terrain-water-motion.gif").toFile())
                    .use { stream ->
                        gif.output = stream; gif.prepareWriteSequence(null)
                        repeat(32) { index ->
                            val image = renderFull(
                                program,
                                fullSource,
                                scene.width,
                                scene.height,
                                mask,
                                1f,
                                0f,
                                0f,
                                1f,
                                0.41f + index * 0.1f,
                                program
                            )
                            val metadata = gif.getDefaultImageMetadata(
                                javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), null
                            )
                            val root =
                                metadata.getAsTree("javax_imageio_gif_image_1.0") as javax.imageio.metadata.IIOMetadataNode
                            val control = root.getElementsByTagName("GraphicControlExtension")
                                .item(0) as javax.imageio.metadata.IIOMetadataNode
                            control.setAttribute("delayTime", "10")
                            if (index == 0) {
                                val ext = javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions")
                                val loop = javax.imageio.metadata.IIOMetadataNode("ApplicationExtension")
                                loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute(
                                    "authenticationCode",
                                    "2.0"
                                )
                                loop.userObject = byteArrayOf(1, 0, 0); ext.appendChild(loop); root.appendChild(ext)
                            }
                            metadata.setFromTree("javax_imageio_gif_image_1.0", root)
                            gif.writeToSequence(javax.imageio.IIOImage(image, null, metadata), null)
                        }
                        gif.endWriteSequence()
                    }
            } finally {
                gif.dispose()
            }

        } finally {
            GL20.glUseProgram(0)
            textures.forEach { runCatching { GL11.glDeleteTextures(it) } }
            if (diagnosticProgram != 0) GL20.glDeleteProgram(diagnosticProgram)
            if (program != 0) GL20.glDeleteProgram(program)
            if (vertexShader != 0) GL20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader)
            if (diagnosticFragmentShader != 0) GL20.glDeleteShader(diagnosticFragmentShader)
            pbuffer.destroy()
            restoreNativePath(previousNativePath)
        }
    }

    /** Regression checks for relief that a simple count of changed pixels cannot detect. */
    private fun verifyTerrainRelief(program: Int, width: Int, height: Int) {
        val textures = mutableListOf<Int>()
        fun texture(image: BufferedImage, unit: Int = GL13.GL_TEXTURE0) =
            uploadTexture(image, unit).also { textures += it.id }

        fun solidMask(lava: Boolean = false) = BufferedImage(
            width / TILE * MASK_RESOLUTION,
            height / TILE * MASK_RESOLUTION,
            BufferedImage.TYPE_INT_ARGB
        ).apply {
            for (y in 0 until this.height) for (x in 0 until this.width)
                setRGB(x, y, terrainMaterialMaskPixel(false, -6f, lava && x in 48..55))
        }

        fun source(color: (Int, Int) -> Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, color(x, y))
        }

        fun render(source: GpuTexture, mask: GpuTexture, strength: Float = 1f, time: Float = 0.41f) =
            renderFull(program, source, width, height, mask, 1f, 0f, 0f, 1f, time, program, strength = strength)
        try {
            val mask = texture(solidMask(), GL13.GL_TEXTURE1)
            val flat = texture(makeGutteredSource(source { _, _ -> Color(70, 135, 60).rgb }, 0, width))
            val unlit = render(flat, mask, 0f)
            val lit = render(flat, mask)
            val later = render(flat, mask, time = 9.41f)
            assertEquals(
                0,
                maxChannelDelta(lit, later),
                "terrain relief must be static, independent of water animation time"
            )
            fun greenRange(image: BufferedImage): Int {
                val values = (20..250).flatMap { y -> (20..360).map { x -> (image.getRGB(x, y) ushr 8) and 255 } }
                return values.max() - values.min()
            }
            assertTrue(greenRange(unlit) <= 1, "zero lighting strength must disable broad terrain relief")
            assertTrue(
                greenRange(lit) >= 12,
                "flat repeated grass must regain broad world-anchored light/shadow variation"
            )

            val rockImage = source { x, y ->
                val v = (105 + 24 * kotlin.math.sin(x * 0.18) + 16 * kotlin.math.sin(y * 0.13)).toInt()
                Color(v + 6, v, v - 6).rgb
            }
            val rock = texture(makeGutteredSource(rockImage, 0, width))
            val rockLit = render(rock, mask)
            var sunGain = 0.0;
            var shadeGain = 0.0;
            var sunCount = 0;
            var shadeCount = 0
            for (y in 20..250) for (x in 20..360) {
                val gradient = 24 * 0.18 * kotlin.math.cos(x * 0.18) + 16 * 0.13 * kotlin.math.cos(y * 0.13)
                val original = (rockImage.getRGB(x, y) ushr 8) and 255
                val gain = ((rockLit.getRGB(x, y) ushr 8) and 255).toDouble() / original
                if (gradient > 3.0) {
                    sunGain += gain; sunCount++
                }
                if (gradient < -3.0) {
                    shadeGain += gain; shadeCount++
                }
            }
            val directionalContrast = sunGain / sunCount - shadeGain / shadeCount
            assertTrue(
                directionalContrast > 0.045,
                "source relief must distinguish sun-facing and sheltered slopes; gain difference=$directionalContrast"
            )

            // A neighbour light needs a world-pixel radius, not a UV radius multiplied twice.
            val lavaMask = texture(solidMask(lava = true), GL13.GL_TEXTURE1)
            val spill = render(flat, lavaMask)
            assertTrue(
                pixelDifferenceCount(lit, spill, 104..116, 30..240) > 200,
                "lava must illuminate adjacent ground up to 16 world pixels away"
            )
            assertEquals(0, pixelDifferenceCount(lit, spill, 20..80, 30..240), "lava spill must stay local")

            val moltenSource = source { x, y ->
                if (x in 120..139) Color(150 + (x + y) % 35, 35 + (x * 3 + y) % 20, 8).rgb else Color(70, 135, 60).rgb
            }
            val molten = texture(makeGutteredSource(moltenSource, 0, width))
            val lavaNow = render(molten, lavaMask, time = 0.41f)
            val lavaLater = render(molten, lavaMask, time = 4.41f)
            assertTrue(
                pixelDifferenceCount(lavaNow, lavaLater, 122..137, 30..240) > 800,
                "Molten seams must move inside lava instead of just grading its tint"
            )
            assertEquals(
                0, pixelDifferenceCount(lavaNow, lavaLater, 20..80, 30..240),
                "Lava animation must leave distant ground stable"
            )
            assertTrue((30..240).all { y ->
                (122..137).all { x ->
                    val color = Color(lavaNow.getRGB(x, y)); color.red < 250 && color.red > color.green
                }
            }, "Molten emission must retain warm detail and avoid clipping white")
            val lavaPreview = BufferedImage(width * 2, height, BufferedImage.TYPE_INT_RGB)
            lavaPreview.createGraphics()
                .apply { drawImage(lavaNow, 0, 0, null); drawImage(lavaLater, width, 0, null); dispose() }
            ImageIO.write(lavaPreview, "png", Path.of("build/ui-regression/lava-motion.png").toFile())

            val snow = texture(makeGutteredSource(source { _, _ -> Color(225, 225, 225).rgb }, 0, width))
            val snowLit = render(snow, mask)
            assertTrue(greenRange(snowLit) < greenRange(lit), "pale matte snow should have softer relief than grass")
            assertTrue(
                (20..250).all { y -> (20..360).all { x -> ((snowLit.getRGB(x, y) ushr 8) and 255) in 195..250 } },
                "matte snow must retain white headroom and must not acquire deep artificial pits"
            )
            val comparison = BufferedImage(width * 3, height * 2, BufferedImage.TYPE_INT_RGB)
            comparison.createGraphics().apply {
                drawImage(unlit, 0, 0, null); drawImage(lit, width, 0, null); drawImage(spill, width * 2, 0, null)
                drawImage(rockImage, 0, height, null); drawImage(rockLit, width, height, null); drawImage(
                snowLit,
                width * 2,
                height,
                null
            ); dispose()
            }
            val output = Path.of("build/ui-regression")
            ImageIO.write(comparison, "png", output.resolve("terrain-relief-regression.png").toFile())
            Files.writeString(
                output.resolve("terrain-relief-metrics.txt"),
                "grass light range=${greenRange(lit)}; snow light range=${greenRange(snowLit)}; sun/shade gain difference=$directionalContrast; static terrain identical"
            )
        } finally {
            GL20.glUseProgram(0); textures.forEach(GL11::glDeleteTextures); GL13.glActiveTexture(GL13.GL_TEXTURE0)
        }
    }

    /** Actual production terrain, silhouette-shadow and post shaders over installed snow/unit art. */
    private fun writeReferenceLightingScene(assets: Path, terrainProgram: Int, vertex: Int) {
        val drawable = assets.parent.resolve("res/drawable")
        val snowAtlas = loadNativeAtlas(assets.resolve("tilesets/bitmaps/snow.png"))
        val snow = BufferedImage(VIEW_WIDTH, VIEW_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = snow.createGraphics()
        for (y in 0 until VIEW_HEIGHT / TILE) for (x in 0 until VIEW_WIDTH / TILE)
            drawAtlasCell(g, snowAtlas, x * TILE, y * TILE, (x + y) % 3)
        g.dispose()
        val images = listOf(
            ImageIO.read(drawable.resolve("land_factory.png").toFile()),
            ImageIO.read(drawable.resolve("extractor.png").toFile()).getSubimage(0, 0, 37, 59),
            ImageIO.read(drawable.resolve("heavy_tank.png").toFile()).getSubimage(0, 0, 33, 36),
            ImageIO.read(drawable.resolve("heavy_tank_turret.png").toFile())
        )
        val textures = mutableListOf<Int>()
        val programs = mutableListOf<Int>()
        val fragments = mutableListOf<Int>()
        fun texture(img: BufferedImage) = uploadTexture(img, GL13.GL_TEXTURE0).also { textures += it.id }
        fun program(source: String): Int {
            val fragment = compile(GL20.GL_FRAGMENT_SHADER, source).also { fragments += it }
            return GL20.glCreateProgram().also {
                GL20.glAttachShader(it, vertex); GL20.glAttachShader(it, fragment); GL20.glLinkProgram(it)
                assertEquals(
                    GL11.GL_TRUE,
                    GL20.glGetProgrami(it, GL20.GL_LINK_STATUS),
                    GL20.glGetProgramInfoLog(it, 4096)
                )
                programs += it
            }
        }
        try {
            val snowTexture = texture(makeGutteredSource(snow, 0, VIEW_WIDTH))
            val maskImage = BufferedImage(
                VIEW_WIDTH / TILE * MASK_RESOLUTION,
                VIEW_HEIGHT / TILE * MASK_RESOLUTION,
                BufferedImage.TYPE_INT_ARGB
            )
            for (y in 0 until maskImage.height) for (x in 0 until maskImage.width)
                maskImage.setRGB(x, y, terrainMaterialMaskPixel(false, -6f, false))
            val mask = texture(maskImage)
            val sprites = images.map(::texture)
            val shadowSprites = images.mapIndexed { index, image ->
                if (index > 1) sprites[index] else {
                    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                    val filled = solidBuildingShadowPixels(pixels, image.width, image.height)
                    val mask = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    mask.setRGB(0, 0, image.width, image.height, filled, 0, image.width)
                    texture(mask)
                }
            }
            val shadow = program(DesktopWorldProjectedShadows.FRAGMENT_SHADER)
            val post = program(DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER)
            val materialProgram = program(DesktopWorldUnitMaterials.FRAGMENT_SHADER)
            val materialBackground = program(DesktopWorldUnitMaterials.BACKGROUND_FRAGMENT_SHADER)

            data class Placement(val image: Int, val x: Float, val y: Float)

            val placements = listOf(
                Placement(0, 55f, 48f),
                Placement(1, 310f, 50f),
                Placement(1, 310f, 120f),
                Placement(2, 155f, 70f),
                Placement(2, 205f, 84f),
                Placement(2, 157f, 131f),
                Placement(2, 210f, 145f),
                Placement(2, 265f, 175f)
            )

            fun quad(
                tex: GpuTexture,
                x: Float,
                y: Float,
                w: Float,
                h: Float,
                uw: Float = 1f,
                vh: Float = 1f,
                alpha: Float = 1f
            ) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.id)
                GL11.glColor4f(1f, 1f, 1f, alpha); GL11.glBegin(GL11.GL_QUADS)
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(x, y)
                GL11.glTexCoord2f(uw, 0f); GL11.glVertex2f(x + w, y)
                GL11.glTexCoord2f(uw, vh); GL11.glVertex2f(x + w, y + h)
                GL11.glTexCoord2f(0f, vh); GL11.glVertex2f(x, y + h); GL11.glEnd()
            }

            fun draw(
                enhanced: Boolean,
                reflections: Boolean = true,
                highlights: Boolean = true,
                bloom: Boolean = true,
                time: Float = 0.41f,
                steps: Float = 6f
            ): BufferedImage {
                GL11.glDisable(GL11.GL_BLEND)
                renderFull(
                    if (enhanced) terrainProgram else 0,
                    snowTexture,
                    VIEW_WIDTH,
                    VIEW_HEIGHT,
                    mask,
                    1f,
                    0f,
                    0f,
                    1f,
                    0.41f,
                    terrainProgram
                )
                GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
                val beforeShadow = if (enhanced) readFrame() else null
                if (enhanced) {
                    GL20.glUseProgram(shadow); GL20.glUniform1i(requiredUniform(shadow, "u_texture"), 0)
                    for (p in placements) {
                        val image = images[p.image];
                        val tex = shadowSprites[p.image]
                        val uw = image.width.toFloat() / tex.width;
                        val vh = image.height.toFloat() / tex.height
                        GL20.glUniform4f(requiredUniform(shadow, "u_shadowUv"), 0f, 0f, uw, vh)
                        GL20.glUniform2f(requiredUniform(shadow, "u_shadowTexel"), 1f / tex.width, 1f / tex.height)
                        val offset = DesktopWorldProjectedShadows.projectedOffset(image.width * 0.42f, 0f)
                        quad(
                            tex,
                            p.x + offset.first,
                            p.y + offset.second,
                            image.width * 1.04f,
                            image.height * (if (p.image <= 1) 1f else 0.90f),
                            uw,
                            vh,
                            96f / 255f
                        )
                    }
                }
                if (enhanced) {
                    val cast = readFrame()
                    val factory = placements.first()
                    val sprite = images[factory.image]
                    val offset = DesktopWorldProjectedShadows.projectedOffset(sprite.width * 0.42f, 0f).first
                    // Stay inside the factory cell so neighbouring placements cannot contribute.
                    val castBounds = changedBounds(
                        beforeShadow!!, cast,
                        factory.x.toInt()..(factory.x + sprite.width + 45).toInt(),
                        factory.y.toInt()..(factory.y + sprite.height + 45).toInt()
                    )
                    assertTrue(
                        castBounds[0] != Int.MAX_VALUE,
                        "the shadow pass must draw a silhouette for the factory"
                    )
                    // A projected shadow is bounded by the object's own height and the sun angle.
                    // Footprint-proportional displacement detached buildings from their base.
                    val spread = sprite.width * 0.03f + 4f
                    assertTrue(
                        castBounds[2] <= factory.x + sprite.width + offset + spread,
                        "factory shadow must stay attached on the right: ${castBounds[2]} " +
                                "sprite=${sprite.width}x${sprite.height} offset=$offset spread=$spread"
                    )
                    assertTrue(
                        castBounds[3] <= factory.y + sprite.height + offset + spread,
                        "factory shadow must stay attached below: ${castBounds[3]}"
                    )
                }
                GL20.glUseProgram(0)
                for (p in placements) {
                    val image = images[p.image];
                    val tex = sprites[p.image]
                    quad(
                        tex,
                        p.x,
                        p.y,
                        image.width.toFloat(),
                        image.height.toFloat(),
                        image.width.toFloat() / tex.width,
                        image.height.toFloat() / tex.height
                    )
                    if (p.image == 2) {
                        val turret = images[3];
                        val t = sprites[3]
                        quad(
                            t,
                            p.x + 8.5f,
                            p.y - 7f,
                            turret.width.toFloat(),
                            turret.height.toFloat(),
                            turret.width.toFloat() / t.width,
                            turret.height.toFloat() / t.height
                        )
                    }
                }
                GL11.glDisable(GL11.GL_BLEND)
                if (enhanced) {
                    val combined = texture(readFrame())
                    GL11.glClearColor(0f, 0f, 0f, 1f); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                    GL20.glUseProgram(materialBackground)
                    GL20.glUniform1i(requiredUniform(materialBackground, "u_texture"), 0)
                    GL20.glUniform2f(requiredUniform(materialBackground, "u_worldOrigin"), 0f, 0f)
                    GL20.glUniform2f(
                        requiredUniform(materialBackground, "u_viewWorldSize"),
                        VIEW_WIDTH.toFloat(),
                        VIEW_HEIGHT.toFloat()
                    )
                    GL20.glUniform2f(
                        requiredUniform(materialBackground, "u_maskExtent"),
                        maskImage.width.toFloat() / mask.width,
                        maskImage.height.toFloat() / mask.height
                    )
                    GL20.glUniform2f(
                        requiredUniform(materialBackground, "u_maskTexel"),
                        1f / mask.width,
                        1f / mask.height
                    )
                    GL20.glUniform2f(
                        requiredUniform(materialBackground, "u_maskWorldScale"),
                        maskImage.width.toFloat() / mask.width / VIEW_WIDTH,
                        maskImage.height.toFloat() / mask.height / VIEW_HEIGHT
                    )
                    quad(
                        mask,
                        0f,
                        0f,
                        VIEW_WIDTH.toFloat(),
                        VIEW_HEIGHT.toFloat(),
                        maskImage.width.toFloat() / mask.width,
                        maskImage.height.toFloat() / mask.height
                    )
                    GL20.glUseProgram(materialProgram); GL20.glUniform1i(
                        requiredUniform(materialProgram, "u_texture"),
                        0
                    )
                    GL11.glEnable(GL11.GL_BLEND)
                    for (p in placements) {
                        val image = images[p.image];
                        val tex = sprites[p.image]
                        quad(
                            tex,
                            p.x,
                            p.y,
                            image.width.toFloat(),
                            image.height.toFloat(),
                            image.width.toFloat() / tex.width,
                            image.height.toFloat() / tex.height
                        )
                    }
                    GL11.glDisable(GL11.GL_BLEND)
                    val material = texture(readFrame())
                    GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, material.id)
                    GL13.glActiveTexture(GL13.GL_TEXTURE0)
                    GL20.glUseProgram(post);
                    GL20.glUniform1i(requiredUniform(post, "u_sceneMaterials"), 1)
                    GL20.glUniform1f(requiredUniform(post, "u_materialPass"), 1f)
                    GL20.glUniform1f(requiredUniform(post, "u_postSSR"), if (reflections) 1f else 0f)
                    GL20.glUniform1f(requiredUniform(post, "u_surfaceHighlights"), if (highlights) 1f else 0f)
                    GL20.glUniform1f(requiredUniform(post, "u_dynamicBloom"), if (bloom) 1f else 0f)
                    GL20.glUniform1f(requiredUniform(post, "u_wetStrength"), 1f)
                    GL20.glUniform1f(requiredUniform(post, "u_postTraceSteps"), steps)
                    GL20.glUniform2f(
                        requiredUniform(post, "u_materialUvScale"),
                        combined.width.toFloat() / material.width,
                        combined.height.toFloat() / material.height
                    )
                    GL20.glUniform2f(
                        requiredUniform(post, "u_worldPerUv"),
                        combined.width.toFloat(),
                        combined.height.toFloat()
                    )
                    GL20.glUniform2f(
                        requiredUniform(post, "u_sceneExtent"),
                        VIEW_WIDTH.toFloat() / combined.width,
                        VIEW_HEIGHT.toFloat() / combined.height
                    )
                    GL20.glUniform1i(requiredUniform(post, "u_texture"), 0)
                    GL20.glUniform1f(requiredUniform(post, "u_sunStrength"), 1f)
                    quad(
                        combined,
                        0f,
                        0f,
                        VIEW_WIDTH.toFloat(),
                        VIEW_HEIGHT.toFloat(),
                        VIEW_WIDTH.toFloat() / combined.width,
                        VIEW_HEIGHT.toFloat() / combined.height
                    )
                }
                return readFrame()
            }

            val original = draw(false);
            val enhanced = draw(true)
            val plain = draw(true, false, false, false)
            val reflectionOnly = draw(true, true, false, false)
            val glossOnly = draw(true, false, true, false)
            val fewerSteps = draw(true, true, false, false, steps = 2f)
            val noBloom = draw(true, true, true, false)
            assertEquals(
                0, pixelDifferenceCount(plain, reflectionOnly, 55..105, 48..112),
                "dry-ground buildings must not acquire screen-space self reflections"
            )
            assertEquals(
                0, pixelDifferenceCount(fewerSteps, reflectionOnly, 55..105, 48..112),
                "reflection quality must not recolour a dry-ground building"
            )
            for (y in images[0].height / 4 until images[0].height * 3 / 4) for (x in images[0].width / 4 until images[0].width * 3 / 4) {
                if ((images[0].getRGB(x, y) ushr 24) >= 254) {
                    val px = 55 + x;
                    val py = 48 + y
                    assertEquals(
                        plain.getRGB(px, py),
                        glossOnly.getRGB(px, py),
                        "opaque factory pixels must not receive synthetic gloss"
                    )
                    assertEquals(
                        noBloom.getRGB(px, py),
                        enhanced.getRGB(px, py),
                        "factory pixels must not receive bloom"
                    )
                }
            }
            assertTrue(
                pixelDifferenceCount(plain, glossOnly, 0..40, 140..260) > 30,
                "ground highlights must remain independently available"
            )
            val result = BufferedImage(VIEW_WIDTH * 2, VIEW_HEIGHT, BufferedImage.TYPE_INT_RGB)
            result.createGraphics()
                .apply { drawImage(original, 0, 0, null); drawImage(enhanced, VIEW_WIDTH, 0, null); dispose() }
            ImageIO.write(result, "png", Path.of("build/ui-regression/reference-sunlight-comparison.png").toFile())
            assertTrue(
                pixelDifferenceCount(original, enhanced, 0 until VIEW_WIDTH, 0 until VIEW_HEIGHT) > 1000,
                "real sprite silhouettes and highlight postprocessing must visibly affect the snow scene"
            )
        } finally {
            GL20.glUseProgram(0); textures.forEach(GL11::glDeleteTextures); programs.forEach(GL20::glDeleteProgram); fragments.forEach(
                GL20::glDeleteShader
            )
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        assertEquals(
            GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS),
            "terrain shader compile failed: ${GL20.glGetShaderInfoLog(shader, 4096)}"
        )
        return shader
    }

    private fun requiredUniform(program: Int, name: String): Int = GL20.glGetUniformLocation(program, name).also {
        assertTrue(it >= 0, "production terrain shader should expose $name")
    }

    private fun setCommonUniforms(
        program: Int,
        worldWidth: Int,
        worldHeight: Int,
        mask: GpuTexture,
        source: GpuTexture,
        bufferScale: Float,
        viewZoom: Float,
        time: Float
    ) {
        GL20.glUniform1f(requiredUniform(program, "u_time"), time)
        GL20.glUniform1f(requiredUniform(program, "u_cacheScale"), bufferScale)
        GL20.glUniform1f(requiredUniform(program, "u_lightStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_waveStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_wetStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_terrainHighlights"), 1f)
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheWorldOrigin"),
            -source.gutter / bufferScale, -source.gutter / bufferScale
        )
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheUvWorldScale"),
            source.width / bufferScale,
            source.height / bufferScale
        )
        GL20.glUniform2f(
            requiredUniform(program, "u_maskWorldScale"),
            (worldWidth / TILE * MASK_RESOLUTION).toFloat() / mask.width / worldWidth,
            (worldHeight / TILE * MASK_RESOLUTION).toFloat() / mask.height / worldHeight
        )
        GL20.glUniform2f(
            requiredUniform(program, "u_maskExtent"),
            (worldWidth / TILE * MASK_RESOLUTION).toFloat() / mask.width,
            (worldHeight / TILE * MASK_RESOLUTION).toFloat() / mask.height
        )
        GL20.glUniform2f(requiredUniform(program, "u_maskTexel"), 1f / mask.width, 1f / mask.height)
        GL20.glUniform2f(requiredUniform(program, "u_sourceTexel"), 1f / source.width, 1f / source.height)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, VIEW_WIDTH.toDouble(), VIEW_HEIGHT.toDouble(), 0.0, -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
        GL11.glScalef(viewZoom / bufferScale, viewZoom / bufferScale, 1f)
    }

    private fun renderFull(
        activeProgram: Int,
        source: GpuTexture,
        width: Int,
        height: Int,
        mask: GpuTexture,
        bufferScale: Float,
        cameraX: Float,
        cameraY: Float,
        viewZoom: Float,
        time: Float,
        program: Int,
        strength: Float = 1f,
        waveStrength: Float = 1f
    ): BufferedImage {
        configure(activeProgram, program, width, height, mask, source, bufferScale, cameraX, cameraY, viewZoom, time)
        if (activeProgram != 0) {
            GL20.glUniform1f(requiredUniform(program, "u_lightStrength"), strength)
            GL20.glUniform1f(requiredUniform(program, "u_waveStrength"), waveStrength)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.id)
        drawBlock(
            source,
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            bufferScale,
            cameraX,
            cameraY
        )
        return readFrame()
    }

    private fun renderSplit(
        program: Int,
        left: GpuTexture,
        right: GpuTexture,
        width: Int,
        height: Int,
        mask: GpuTexture,
        bufferScale: Float,
        cameraX: Float,
        cameraY: Float,
        viewZoom: Float,
        time: Float
    ): BufferedImage {
        configure(program, program, width, height, mask, left, bufferScale, cameraX, cameraY, viewZoom, time)
        val half = width / 2
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, left.id)
        drawBlock(
            left,
            0f,
            0f,
            half.toFloat(),
            height.toFloat(),
            0f,
            0f,
            half.toFloat(),
            height.toFloat(),
            bufferScale,
            cameraX,
            cameraY
        )
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, right.id)
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheWorldOrigin"),
            half - right.gutter / bufferScale,
            -right.gutter / bufferScale
        )
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheUvWorldScale"),
            right.width / bufferScale,
            right.height / bufferScale
        )
        GL20.glUniform1f(requiredUniform(program, "u_cacheScale"), bufferScale)
        GL20.glUniform1f(requiredUniform(program, "u_lightStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_waveStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_wetStrength"), 1f)
        GL20.glUniform1f(requiredUniform(program, "u_terrainHighlights"), 1f)
        drawBlock(
            right,
            half.toFloat(),
            0f,
            width.toFloat(),
            height.toFloat(),
            half.toFloat(),
            0f,
            width.toFloat(),
            height.toFloat(),
            bufferScale,
            cameraX,
            cameraY
        )
        return readFrame()
    }

    private fun configure(
        activeProgram: Int,
        shaderProgram: Int,
        width: Int,
        height: Int,
        mask: GpuTexture,
        source: GpuTexture,
        bufferScale: Float,
        cameraX: Float,
        cameraY: Float,
        viewZoom: Float,
        time: Float
    ) {
        GL11.glViewport(0, 0, width, height)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL20.glUseProgram(activeProgram)
        if (activeProgram != 0) {
            setCommonUniforms(shaderProgram, width, height, mask, source, bufferScale, viewZoom, time)
            GL20.glUniform1i(requiredUniform(shaderProgram, "u_texture"), 0)
            GL20.glUniform1i(requiredUniform(shaderProgram, "u_terrainMask"), 1)
        } else {
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glLoadIdentity()
            GL11.glOrtho(0.0, VIEW_WIDTH.toDouble(), VIEW_HEIGHT.toDouble(), 0.0, -1.0, 1.0)
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glLoadIdentity()
            GL11.glScalef(viewZoom, viewZoom, 1f)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.id)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
    }

    private fun drawBlock(
        texture: GpuTexture,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        sourceX0: Float,
        sourceY0: Float,
        sourceX1: Float,
        sourceY1: Float,
        bufferScale: Float,
        cameraX: Float,
        cameraY: Float
    ) {
        val u0 = texture.gutter.toFloat() / texture.width
        val u1 = (texture.gutter + (sourceX1 - sourceX0) * bufferScale) / texture.width
        val v0 = texture.gutter.toFloat() / texture.height
        val v1 = (texture.gutter + (sourceY1 - sourceY0) * bufferScale) / texture.height
        val left = (x0 - cameraX) * bufferScale
        val right = (x1 - cameraX) * bufferScale
        val top = (y0 - cameraY) * bufferScale
        val bottom = (y1 - cameraY) * bufferScale
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(left, top)
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(right, top)
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(right, bottom)
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(left, bottom)
        GL11.glEnd()
    }

    private fun readFrame(): BufferedImage {
        GL11.glFinish()
        val rgba = BufferUtils.createByteBuffer(VIEW_WIDTH * VIEW_HEIGHT * 4)
        GL11.glReadPixels(0, 0, VIEW_WIDTH, VIEW_HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba)
        val image = BufferedImage(VIEW_WIDTH, VIEW_HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until VIEW_HEIGHT) for (x in 0 until VIEW_WIDTH) {
            val i = (y * VIEW_WIDTH + x) * 4
            image.setRGB(
                x, VIEW_HEIGHT - 1 - y,
                ((rgba.get(i).toInt() and 255) shl 16) or ((rgba.get(i + 1).toInt() and 255) shl 8) or (rgba.get(i + 2)
                    .toInt() and 255)
            )
        }
        return image
    }

    private fun makeGutteredSource(scene: BufferedImage, startX: Int, width: Int, cacheScale: Int = 1): BufferedImage {
        val result = BufferedImage(
            width * cacheScale + GUTTER * 2,
            scene.height * cacheScale + GUTTER * 2,
            BufferedImage.TYPE_INT_ARGB
        )
        for (y in 0 until result.height) for (x in 0 until result.width) {
            val worldX = (startX + Math.floorDiv(x - GUTTER, cacheScale)).coerceIn(0, scene.width - 1)
            val worldY = Math.floorDiv(y - GUTTER, cacheScale).coerceIn(0, scene.height - 1)
            result.setRGB(x, y, scene.getRGB(worldX, worldY))
        }
        return result
    }

    private fun renderDiagnosticBlock(
        program: Int,
        texture: GpuTexture,
        cacheScale: Float,
        cameraX: Float,
        cameraY: Float,
        viewZoom: Float,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
        sampleWorldX: Float,
        sampleWorldY: Float
    ): IntArray {
        GL11.glViewport(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL20.glUseProgram(program)
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheWorldOrigin"), originX - texture.gutter / cacheScale,
            originY - texture.gutter / cacheScale
        )
        GL20.glUniform2f(
            requiredUniform(program, "u_cacheUvWorldScale"),
            texture.width / cacheScale,
            texture.height / cacheScale
        )
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, VIEW_WIDTH.toDouble(), VIEW_HEIGHT.toDouble(), 0.0, -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
        GL11.glScalef(viewZoom / cacheScale, viewZoom / cacheScale, 1f)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.id)
        drawBlock(
            texture, originX, originY, originX + width, originY + height,
            originX, originY, originX + width, originY + height, cacheScale, cameraX, cameraY
        )
        val screenX = ((sampleWorldX - cameraX) * viewZoom).toInt()
        val screenY = ((sampleWorldY - cameraY) * viewZoom).toInt()
        return rgbAt(readFrame(), screenX, screenY)
    }

    private fun rgbAt(image: BufferedImage, x: Int, y: Int): IntArray {
        val rgb = image.getRGB(x, y)
        return intArrayOf((rgb ushr 16) and 255, (rgb ushr 8) and 255, rgb and 255)
    }

    private fun colorDistance(a: IntArray, b: IntArray): Int =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    private fun encodeMask(scene: BufferedImage): BufferedImage {
        val width = scene.width / TILE * MASK_RESOLUTION
        val height = scene.height / TILE * MASK_RESOLUTION
        val coverage = FloatArray(width * height)
        for (ty in 0 until scene.height / TILE) for (tx in 0 until scene.width / TILE) {
            val kind = when {
                tx in 10..15 && ty in 3..9 -> TerrainWaterTileKind.FULL_WATER
                tx in 9..16 && ty in 2..10 -> TerrainWaterTileKind.MIXED_SHORELINE
                else -> TerrainWaterTileKind.LAND
            }
            val source = TerrainWaterTilePixelSource(
                android.graphics.Rect(
                    tx * TILE,
                    ty * TILE,
                    (tx + 1) * TILE,
                    (ty + 1) * TILE
                )
            ) { x, y -> scene.getRGB(x, y) }
            val sample = rasterizeTerrainTile(kind, MASK_RESOLUTION, source)
            for (y in 0 until MASK_RESOLUTION) for (x in 0 until MASK_RESOLUTION)
                coverage[(ty * MASK_RESOLUTION + y) * width + tx * MASK_RESOLUTION + x] =
                    sample.waterCoverage[y * MASK_RESOLUTION + x]
        }
        val distances = terrainCoastDistances(
            BooleanArray(coverage.size) { coverage[it] >= 0.5f },
            width,
            height,
            6f * MASK_RESOLUTION
        )
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setRGB(
                    x,
                    y,
                    terrainMaterialMaskPixel(coverage[y * width + x], distances[y * width + x] / MASK_RESOLUTION, 0f)
                )
            }
        }
    }

    /** Add a dark, cool native-style fog tint to an unexplored 4x4-tile area. */
    private fun fogTintedScene(scene: BufferedImage): BufferedImage = BufferedImage(
        scene.width, scene.height, BufferedImage.TYPE_INT_ARGB
    ).apply {
        for (y in 0 until scene.height) for (x in 0 until scene.width) {
            val source = Color(scene.getRGB(x, y), true)
            val inFog = x in TILE..(5 * TILE - 1) && y in (8 * TILE)..(12 * TILE - 1)
            val tinted = if (inFog) Color(
                (source.red * 0.48f + 23f * 0.52f).toInt(),
                (source.green * 0.48f + 31f * 0.52f).toInt(),
                (source.blue * 0.48f + 44f * 0.52f).toInt(),
                source.alpha
            ) else source
            setRGB(x, y, tinted.rgb)
        }
    }

    private fun buildWaterMask(width: Int, height: Int): BooleanArray {
        val result = BooleanArray(width * height)
        // Match the 3x3 native longgrass/shallow-water ridge artwork border tiles below.
        for (y in 3..9) for (x in 10..15) result[y * width + x] = true
        return result
    }

    private fun buildNativeCoastScene(assetRoot: Path): BufferedImage {
        val bitmaps = assetRoot.resolve("tilesets/bitmaps")
        fun image(name: String) = loadNativeAtlas(bitmaps.resolve(name))
        val grass = image("longgrass.png")
        val dirt = image("dirt.png")
        val dirtRidge = image("longgrass2dirt_ridge.png")
        val stone = image("stone.png")
        val stonePatch = image("longgrass2stone_ridge.png")
        val water = image("water.png")
        val ridge = image("longgrass2shallowwater_ridge.png")
        val tilesWide = 20
        val tilesHigh = 14
        val scene = BufferedImage(tilesWide * TILE, tilesHigh * TILE, BufferedImage.TYPE_INT_ARGB)
        val g = scene.createGraphics()
        try {
            // The first five cells of the grass atlas are plain ground. Later cells are props,
            // trees, ruins and rock fragments, so never sample them as generic ground.
            for (ty in 0 until tilesHigh) for (tx in 0 until tilesWide) {
                val grassCell = (tx * 3 + ty * 2 + (tx + ty) % 3) % 5
                drawAtlasCell(g, grass, tx * TILE, ty * TILE, grassCell)
            }
            // Native ridge borders surround solid material interiors. Dirt uses only its plain
            // first-row cells 0..2; its remaining rows include chroma-key gaps. Stone uses only
            // continuous ground cells, never the boulder/cliff fragments in the left atlas.
            drawTransitionPatch(g, dirtRidge, dirt, originX = 1, originY = 2, size = 5) { x, y -> (x + y) % 3 }
            drawTransitionPatch(g, stonePatch, stone, originX = 4, originY = 7, size = 5) { x, y ->
                val groundCells = intArrayOf(3, 4, 5, 9, 10, 15, 16, 21, 22, 30, 31, 32, 33, 34)
                groundCells[(x * 3 + y * 5) % groundCells.size]
            }
            // Fill the lake with the original native shallow-water frame, then use the original
            // 3x3 ridge atlas edge, corner and fill tiles to form its bank.
            for (ty in 3..9) for (tx in 10..15) {
                g.drawImage(water, tx * TILE, ty * TILE, tx * TILE + TILE, ty * TILE + TILE, 0, 0, TILE, TILE, null)
            }
            for ((tx, ty, id) in listOf(
                Triple(9, 2, 0),
                Triple(10, 2, 1),
                Triple(11, 2, 1),
                Triple(12, 2, 1),
                Triple(13, 2, 1),
                Triple(14, 2, 1),
                Triple(15, 2, 1),
                Triple(16, 2, 2),
                Triple(9, 3, 3),
                Triple(9, 4, 3),
                Triple(9, 5, 3),
                Triple(9, 6, 3),
                Triple(9, 7, 3),
                Triple(9, 8, 3),
                Triple(9, 9, 3),
                Triple(16, 3, 5),
                Triple(16, 4, 5),
                Triple(16, 5, 5),
                Triple(16, 6, 5),
                Triple(16, 7, 5),
                Triple(16, 8, 5),
                Triple(16, 9, 5),
                Triple(9, 10, 6),
                Triple(10, 10, 7),
                Triple(11, 10, 7),
                Triple(12, 10, 7),
                Triple(13, 10, 7),
                Triple(14, 10, 7),
                Triple(15, 10, 7),
                Triple(16, 10, 8)
            )) drawAtlasCell(g, ridge, tx * TILE, ty * TILE, id)

            // Reproduce a native-like unrevealed patch in the source cache; the shader must leave
            // that pre-tinted image untouched where mask B is zero.
            g.color = Color(12, 16, 19, 105)
            g.fillRect(TILE, 8 * TILE, 5 * TILE, 5 * TILE)
        } finally {
            g.dispose()
        }
        return scene
    }

    /** Tiled's TSX `trans="008a76"` turns this exact chroma-key color into transparency. */
    private fun loadNativeAtlas(path: Path): BufferedImage {
        val source = ImageIO.read(path.toFile())
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val argb = source.getRGB(x, y)
            result.setRGB(x, y, if ((argb and 0x00ffffff) == 0x008a76) argb and 0x00ffffff else argb)
        }
        return result
    }

    private fun drawTransitionPatch(
        graphics: Graphics2D,
        transition: BufferedImage,
        material: BufferedImage,
        originX: Int,
        originY: Int,
        size: Int,
        materialCell: (Int, Int) -> Int
    ) {
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            val cell = materialCell(x - 1, y - 1)
            drawAtlasCell(graphics, material, (originX + x) * TILE, (originY + y) * TILE, cell)
        }
        val right = size - 1
        val bottom = size - 1
        drawAtlasCell(graphics, transition, originX * TILE, originY * TILE, 0)
        for (x in 1 until right) drawAtlasCell(graphics, transition, (originX + x) * TILE, originY * TILE, 1)
        drawAtlasCell(graphics, transition, (originX + right) * TILE, originY * TILE, 2)
        for (y in 1 until bottom) {
            drawAtlasCell(graphics, transition, originX * TILE, (originY + y) * TILE, 3)
            drawAtlasCell(graphics, transition, (originX + right) * TILE, (originY + y) * TILE, 5)
        }
        drawAtlasCell(graphics, transition, originX * TILE, (originY + bottom) * TILE, 6)
        for (x in 1 until right) drawAtlasCell(graphics, transition, (originX + x) * TILE, (originY + bottom) * TILE, 7)
        drawAtlasCell(graphics, transition, (originX + right) * TILE, (originY + bottom) * TILE, 8)
    }

    private fun drawAtlasCell(graphics: Graphics2D, atlas: BufferedImage, dx: Int, dy: Int, cell: Int) {
        val columns = atlas.width / TILE
        val sx = (cell % columns) * TILE
        val sy = (cell / columns) * TILE
        graphics.drawImage(atlas, dx, dy, dx + TILE, dy + TILE, sx, sy, sx + TILE, sy + TILE, null)
    }

    private fun uploadTexture(image: BufferedImage, activeTexture: Int): GpuTexture {
        val width = nextPowerOfTwo(image.width)
        val height = nextPowerOfTwo(image.height)
        val pixels = BufferUtils.createByteBuffer(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val argb = image.getRGB(x.coerceAtMost(image.width - 1), y.coerceAtMost(image.height - 1))
            pixels.put((argb ushr 16).toByte()).put((argb ushr 8).toByte()).put(argb.toByte())
                .put((argb ushr 24).toByte())
        }
        pixels.flip()
        val id = GL11.glGenTextures()
        GL13.glActiveTexture(activeTexture)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            width,
            height,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            pixels
        )
        return GpuTexture(id, width, height, GUTTER)
    }

    private fun maxChannelDelta(a: BufferedImage, b: BufferedImage): Int {
        var maxDelta = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y);
            val q = b.getRGB(x, y)
            for (shift in 0..16 step 8) maxDelta =
                max(maxDelta, abs(((p ushr shift) and 255) - ((q ushr shift) and 255)))
        }
        return maxDelta
    }

    private fun pixelDifferenceCount(a: BufferedImage, b: BufferedImage, xs: IntRange, ys: IntRange): Int {
        var count = 0
        for (y in ys) for (x in xs) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }

    /** Bounding box (minX, minY, maxX, maxY) of pixels that differ, or MAX_VALUE when none do. */
    private fun changedBounds(a: BufferedImage, b: BufferedImage, xs: IntRange, ys: IntRange): IntArray {
        var minX = Int.MAX_VALUE;
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE;
        var maxY = Int.MIN_VALUE
        for (y in ys) for (x in xs) if (a.getRGB(x, y) != b.getRGB(x, y)) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun assertNoNewCyanFringe(before: BufferedImage, after: BufferedImage) {
        // Sample a narrow land-side shoreline strip in the native atlas. Original blue water is
        // allowed; the assertion catches the shader introducing a new saturated cyan rim there.
        var newCyan = 0
        for (y in 62 until 198) for (x in 181 until 187) {
            val original = before.getRGB(x, y)
            val shaded = after.getRGB(x, y)
            val oldBlue = (original and 255) - ((original ushr 16) and 255)
            val newBlue = (shaded and 255) - ((shaded ushr 16) and 255)
            if (newBlue > 88 && oldBlue < 65) newCyan++
        }
        assertTrue(newCyan < 24, "native coast should not gain a bright cyan shoreline outline; pixels=$newCyan")
    }

    private fun writeComparison(scene: BufferedImage, before: BufferedImage, after: BufferedImage) {
        val output = Path.of("build/ui-regression")
        Files.createDirectories(output)
        val comparison = BufferedImage(scene.width * 2, scene.height + 24, BufferedImage.TYPE_INT_RGB)
        val g = comparison.createGraphics()
        try {
            g.color = Color(24, 26, 29); g.fillRect(0, 0, comparison.width, comparison.height)
            g.font = Font("Microsoft YaHei", Font.PLAIN, 12); g.color = Color.WHITE
            g.drawString("原版缓存 (native source)", 6, 17)
            g.drawString("整体材质 shader (production GLSL)", scene.width + 6, 17)
            g.drawImage(before, 0, 24, null); g.drawImage(after, scene.width, 24, null)
        } finally {
            g.dispose()
        }
        ImageIO.write(comparison, "png", output.resolve("native-terrain-material-before-after.png").toFile())
    }

    private fun locateGameAssetRoot(): Path? {
        val roots = listOfNotNull(
            System.getProperty("rwpp.gameAssets")?.let { Path.of(it) },
            System.getenv("RWPP_GAME_ASSETS")?.let { Path.of(it) },
            Path.of("D:/steam/steamapps/common/Rusted Warfare/assets"),
            Path.of("D:/Steam/steamapps/common/Rusted Warfare/assets")
        )
        return roots.firstOrNull { Files.isRegularFile(it.resolve("tilesets/bitmaps/longgrass2shallowwater_ridge.png")) }
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
                    val game =
                        Path.of(match.groupValues[1].replace("\\\\", "/")).resolve("steamapps/common/Rusted Warfare")
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

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private data class GpuTexture(
        val id: Int,
        val width: Int,
        val height: Int,
        val gutter: Int
    )

    private companion object {
        val DIAGNOSTIC_FRAGMENT_SHADER = """
            #version 130
            precision mediump float;
            varying vec2 v_worldPosition;
            void main() {
              gl_FragColor = vec4(v_worldPosition / 512.0, 0.0, 1.0);
            }
        """.trimIndent()
        const val MASK_RESOLUTION = 8
        const val TILE = 20
        const val GUTTER = 2
        const val VIEW_WIDTH = 400
        const val VIEW_HEIGHT = 280
    }
}
