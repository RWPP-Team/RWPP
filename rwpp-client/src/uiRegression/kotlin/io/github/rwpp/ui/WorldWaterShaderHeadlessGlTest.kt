package io.github.rwpp.ui

import io.github.rwpp.graphics.DesktopWorldProjectedShadows
import io.github.rwpp.graphics.DesktopWorldWaterSurfaceEnhancements
import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compiles and draws the production water shader in an LWJGL pbuffer. It does not create a game
 * window. On machines without the Steam LWJGL native or an OpenGL 3 context JUnit reports a skip.
 */
class WorldWaterShaderHeadlessGlTest {
    @Test
    fun waterShaderCompilesAndRendersAnimatedTilesInOffscreenGl() {
        val nativeDir = locateLwjglNativeDirectory()
        Assume.assumeTrue("Steam lwjgl64.dll was not found; offscreen native GL is unavailable", nativeDir != null)

        val previousNativePath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val pbuffer = try {
            Pbuffer(
                512,
                512,
                PixelFormat().withAlphaBits(8).withDepthBits(0),
                null,
                null,
                ContextAttribs(3, 0)
            )
        } catch (failure: Throwable) {
            restoreNativePath(previousNativePath)
            Assume.assumeTrue("LWJGL could not create an OpenGL pbuffer: ${failure.javaClass.simpleName}", false)
            return
        }

        var vertexShader = 0
        var shadowVertexShader = 0
        var fragmentShader = 0
        var shadowFragmentShader = 0
        var program = 0
        var shadowProgram = 0
        var texture = 0
        var spriteTexture = 0
        try {
            try {
                pbuffer.makeCurrent()
            } catch (failure: Throwable) {
                Assume.assumeTrue("LWJGL pbuffer could not become current: ${failure.javaClass.simpleName}", false)
                return
            }
            val version = GL11.glGetString(GL11.GL_VERSION).orEmpty()
            val versionNumbers = Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues
                ?.drop(1)?.mapNotNull(String::toIntOrNull).orEmpty()
            if (versionNumbers.size < 2 || versionNumbers[0] < 3) {
                Assume.assumeTrue("OpenGL 3.0 is required by #version 130; pbuffer reports '$version'", false)
                return
            }

            vertexShader = compile(GL20.GL_VERTEX_SHADER, DesktopWorldWaterSurfaceEnhancements.VERTEX_SHADER)
            shadowVertexShader = compile(GL20.GL_VERTEX_SHADER, NATIVE_PLAIN_VERTEX_SHADER)
            fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, DesktopWorldWaterSurfaceEnhancements.FRAGMENT_SHADER)
            program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            GL20.glLinkProgram(program)
            val linkStatus = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS)
            assertEquals(
                GL11.GL_TRUE,
                linkStatus,
                "water shader link failed: ${GL20.glGetProgramInfoLog(program, 4096)}"
            )

            shadowFragmentShader = compile(GL20.GL_FRAGMENT_SHADER, DesktopWorldProjectedShadows.FRAGMENT_SHADER)
            shadowProgram = GL20.glCreateProgram()
            GL20.glAttachShader(shadowProgram, shadowVertexShader)
            GL20.glAttachShader(shadowProgram, shadowFragmentShader)
            GL20.glLinkProgram(shadowProgram)
            val shadowLinkStatus = GL20.glGetProgrami(shadowProgram, GL20.GL_LINK_STATUS)
            assertEquals(
                GL11.GL_TRUE,
                shadowLinkStatus,
                "sprite-projected shadow shader link failed: ${GL20.glGetProgramInfoLog(shadowProgram, 4096)}"
            )

            texture = makeWaterTexture()
            GL11.glViewport(0, 0, 64, 64)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL20.glUseProgram(program)
            GL20.glUniform1i(uniform(program, "u_texture"), 0)
            GL20.glUniform2f(uniform(program, "u_cameraRender"), 32f, 32f)
            GL20.glUniform1f(uniform(program, "u_zoom"), 1f)
            GL20.glUniform1f(uniform(program, "u_material"), 0f)

            val first = drawTwoAdjacentWaterTiles(program, texture, time = 3.9f)
            val later = drawTwoAdjacentWaterTiles(program, texture, time = 5.2f)
            val changingChannels = first.indices.count { first[it] != later[it] }
            assertTrue(
                changingChannels > 100,
                "the compiled water shader must render moving highlights, not a pass-through"
            )

            val continuousReference = drawOneContinuousWaterSurface(program, texture, time = 3.9f)
            val seamDifference =
                first.indices.maxOf { index -> kotlin.math.abs(first[index] - continuousReference[index]) }
            assertTrue(
                seamDifference <= 1,
                "adjacent atlas tiles must match the same world-space surface rendered continuously; max channel delta=$seamDifference"
            )

            val scaledSplit = drawTwoAdjacentWaterTiles(
                program, texture, time = 3.9f, cameraX = 24f, cameraY = 28f, zoom = 0.5f, renderScale = 1.5f
            )
            val scaledContinuous = drawOneContinuousWaterSurface(
                program, texture, time = 3.9f, cameraX = 24f, cameraY = 28f, zoom = 0.5f, renderScale = 1.5f
            )
            val scaledSeamDifference = scaledSplit.indices.maxOf { index ->
                kotlin.math.abs(scaledSplit[index] - scaledContinuous[index])
            }
            assertTrue(
                scaledSeamDifference <= 1,
                "world phase must stay continuous under camera, zoom, and framebuffer scale transforms; max channel delta=$scaledSeamDifference"
            )
            writeGlPreview(first)
            writeNativeWaterSequence(program)

            spriteTexture = makeSpriteTexture()
            val shadowPixels = drawSpriteShadow(shadowProgram, spriteTexture)
            val bodyShadow = pixel(shadowPixels, 25, 25)
            val transparentHoleShadow = pixel(shadowPixels, 31, 31)
            assertTrue(bodyShadow[3] > 0, "opaque sprite pixels must cast an alpha silhouette shadow")
            assertEquals(0, transparentHoleShadow[3], "transparent holes in the sprite must remain holes in its shadow")
            assertEquals(
                listOf(0, 0, 0),
                transparentHoleShadow.take(3),
                "the projected sprite silhouette must be black, not a rectangular overlay"
            )
            repeat(24) {
                val repeated = drawSpriteShadow(shadowProgram, spriteTexture)
                assertTrue(
                    shadowPixels.contentEquals(repeated),
                    "unchanged sprite shadow must not flicker between frames"
                )
            }
            writeNativeFactoryShadowComparison(shadowProgram)
        } finally {
            if (shadowProgram != 0) GL20.glDeleteProgram(shadowProgram)
            if (shadowFragmentShader != 0) GL20.glDeleteShader(shadowFragmentShader)
            if (shadowVertexShader != 0) GL20.glDeleteShader(shadowVertexShader)
            if (program != 0) GL20.glDeleteProgram(program)
            if (vertexShader != 0) GL20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader)
            if (spriteTexture != 0) GL11.glDeleteTextures(spriteTexture)
            if (texture != 0) GL11.glDeleteTextures(texture)
            pbuffer.destroy()
            restoreNativePath(previousNativePath)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            val log = GL20.glGetShaderInfoLog(shader, 4096)
            GL20.glDeleteShader(shader)
            throw AssertionError("shader compile failed: $log")
        }
        return shader
    }

    private fun uniform(program: Int, name: String): Int {
        val location = GL20.glGetUniformLocation(program, name)
        assertTrue(location >= 0, "shader uniform '$name' was optimized out or missing")
        return location
    }

    private fun makeWaterTexture(): Int {
        val texture = GL11.glGenTextures()
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        val pixels = BufferUtils.createByteBuffer(4 * 4 * 4)
        repeat(4 * 4) { pixels.put(40.toByte()).put(105.toByte()).put(160.toByte()).put(0xff.toByte()) }
        pixels.flip()
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels
        )
        return texture
    }

    /** Actual native water artwork through production GLSL, including continuous frames. */
    private fun writeNativeWaterSequence(program: Int) {
        val asset = Path.of("D:/steam/steamapps/common/Rusted Warfare/assets/tilesets/bitmaps/water.png")
        Assume.assumeTrue("native terrain missing", Files.isRegularFile(asset))
        val tile = ImageIO.read(asset.toFile())
        val texture = GL11.glGenTextures()
        val data = BufferUtils.createByteBuffer(tile.width * tile.height * 4)
        for (y in 0 until tile.height) for (x in 0 until tile.width) {
            val p = tile.getRGB(x, y)
            data.put((p ushr 16).toByte()).put((p ushr 8).toByte()).put(p.toByte()).put(255.toByte())
        }
        data.flip()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            tile.width,
            tile.height,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            data
        )
        fun frame(time: Float, enhanced: Boolean): BufferedImage {
            GL11.glViewport(0, 0, 512, 512)
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity()
            GL11.glOrtho(0.0, 512.0, 512.0, 0.0, -1.0, 1.0)
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity()
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            GL20.glUseProgram(if (enhanced) program else 0)
            if (enhanced) {
                GL20.glUniform1i(uniform(program, "u_texture"), 0)
                GL20.glUniform1f(uniform(program, "u_time"), time)
                GL20.glUniform2f(uniform(program, "u_cameraRender"), 0f, 0f)
                GL20.glUniform1f(uniform(program, "u_zoom"), 1f)
                GL20.glUniform1f(uniform(program, "u_material"), 0f)
            }
            GL11.glColor4f(1f, 1f, 1f, 1f)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f)
            GL11.glTexCoord2f(25.6f, 0f); GL11.glVertex2f(512f, 0f)
            GL11.glTexCoord2f(25.6f, 25.6f); GL11.glVertex2f(512f, 512f)
            GL11.glTexCoord2f(0f, 25.6f); GL11.glVertex2f(0f, 512f)
            GL11.glEnd(); GL11.glFinish()
            val rgba = BufferUtils.createByteBuffer(512 * 512 * 4)
            GL11.glReadPixels(0, 0, 512, 512, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba)
            return BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until 512) for (x in 0 until 512) {
                    val i = (y * 512 + x) * 4
                    setRGB(
                        x,
                        511 - y,
                        ((rgba.get(i).toInt() and 255) shl 16) or ((rgba.get(i + 1)
                            .toInt() and 255) shl 8) or (rgba.get(i + 2).toInt() and 255)
                    )
                }
            }
        }
        try {
            val first = frame(3f, true);
            val same = frame(3f, true);
            val next = frame(3f + 1f / 60f, true)
            var maxStep = 0;
            var saturation = 0.0
            for (y in 0 until 512) for (x in 0 until 512) {
                val a = first.getRGB(x, y);
                val b = next.getRGB(x, y)
                assertEquals(a, same.getRGB(x, y), "same input must be deterministic")
                val c = listOf((a ushr 16) and 255, (a ushr 8) and 255, a and 255)
                saturation += (c.max() - c.min()).toDouble() / c.max().coerceAtLeast(1)
                for (shift in listOf(0, 8, 16)) maxStep =
                    maxOf(maxStep, kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)))
            }
            assertTrue(maxStep <= 12, "60Hz water must not flash: $maxStep")
            assertTrue(saturation / (512 * 512) < 0.55, "avoid electric blue water")
            val output = Path.of("build/ui-regression"); Files.createDirectories(output)
            val comparison = BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB)
            comparison.createGraphics()
                .let { g -> g.drawImage(frame(0f, false), 0, 0, null); g.drawImage(first, 512, 0, null); g.dispose() }
            ImageIO.write(comparison, "png", output.resolve("native-water-comparison.png").toFile())
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            ImageIO.createImageOutputStream(output.resolve("native-water-motion.gif").toFile()).use { stream ->
                writer.output = stream; writer.prepareWriteSequence(null)
                repeat(40) { index ->
                    val img = frame(3f + index * 0.05f, true)
                    val metadata = writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img),
                        null
                    )
                    val root =
                        metadata.getAsTree("javax_imageio_gif_image_1.0") as javax.imageio.metadata.IIOMetadataNode
                    val control = root.getElementsByTagName("GraphicControlExtension")
                        .item(0) as javax.imageio.metadata.IIOMetadataNode
                    control.setAttribute("delayTime", "5")
                    if (index == 0) {
                        val ext = javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions")
                        val loop = javax.imageio.metadata.IIOMetadataNode("ApplicationExtension")
                        loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute("authenticationCode", "2.0")
                        loop.userObject = byteArrayOf(1, 0, 0); ext.appendChild(loop); root.appendChild(ext)
                    }
                    metadata.setFromTree("javax_imageio_gif_image_1.0", root)
                    writer.writeToSequence(javax.imageio.IIOImage(img, null, metadata), null)
                }
                writer.endWriteSequence()
            }
            writer.dispose()
            Files.writeString(
                output.resolve("water-temporal-metrics.txt"),
                "native terrain 512x512; same input identical; 60Hz max channel step=$maxStep; mean saturation=${saturation / (512 * 512)}"
            )
        } finally {
            GL11.glDeleteTextures(texture)
        }
    }

    private fun makeSpriteTexture(): Int {
        val width = 12
        val height = 12
        val texture = GL11.glGenTextures()
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        val pixels = BufferUtils.createByteBuffer(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val body = x in 2..9 && y in 2..9
                val transparentHole = x in 4..7 && y in 4..7
                pixels.put(220.toByte()).put(80.toByte()).put(30.toByte())
                    .put(if (body && !transparentHole) 0xff.toByte() else 0.toByte())
            }
        }
        pixels.flip()
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels
        )
        return texture
    }

    private fun drawSpriteShadow(program: Int, texture: Int): IntArray {
        configureProjection(renderScale = 1f)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        // The production pass composites with SRC_ALPHA; without it the shader's constant RGB
        // would paint transparent holes as well and the test would judge an impossible frame.
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUseProgram(program)
        GL20.glUniform1i(uniform(program, "u_texture"), 0)
        GL20.glUniform4f(uniform(program, "u_shadowUv"), 0f, 0f, 1f, 1f)
        GL20.glUniform2f(uniform(program, "u_shadowTexel"), 1f / 12f, 1f / 12f)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(20f, 20f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(44f, 20f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(44f, 44f)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(20f, 44f)
        GL11.glEnd()
        GL11.glFinish()
        val pixels: ByteBuffer = BufferUtils.createByteBuffer(64 * 64 * 4)
        GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        return IntArray(pixels.remaining()) { index -> pixels.get(index).toInt() and 0xff }
    }

    /** Reference render that evaluates the exact same world coordinates without a tile boundary. */
    private fun drawOneContinuousWaterSurface(
        program: Int,
        texture: Int,
        time: Float,
        cameraX: Float = 32f,
        cameraY: Float = 32f,
        zoom: Float = 1f,
        renderScale: Float = 1f
    ): IntArray {
        configureProjection(renderScale)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUniform1f(uniform(program, "u_time"), time)
        GL20.glUniform2f(uniform(program, "u_cameraRender"), cameraX, cameraY)
        GL20.glUniform1f(uniform(program, "u_zoom"), zoom)
        val left = (32f - cameraX) * zoom
        val right = (96f - cameraX) * zoom
        val top = (32f - cameraY) * zoom
        val bottom = (64f - cameraY) * zoom
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(left, top)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(right, top)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(right, bottom)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(left, bottom)
        GL11.glEnd()
        GL11.glFinish()
        val pixels: ByteBuffer = BufferUtils.createByteBuffer(64 * 64 * 4)
        GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        return IntArray(pixels.remaining()) { index -> pixels.get(index).toInt() and 0xff }
    }

    private fun drawTwoAdjacentWaterTiles(
        program: Int,
        texture: Int,
        time: Float,
        cameraX: Float = 32f,
        cameraY: Float = 32f,
        zoom: Float = 1f,
        renderScale: Float = 1f
    ): IntArray {
        configureProjection(renderScale)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUniform1f(uniform(program, "u_time"), time)
        GL20.glUniform2f(uniform(program, "u_cameraRender"), cameraX, cameraY)
        GL20.glUniform1f(uniform(program, "u_zoom"), zoom)
        for (tileX in 0..1) {
            val worldLeft = 32f + tileX * 32f
            val left = (worldLeft - cameraX) * zoom
            val right = (worldLeft + 32f - cameraX) * zoom
            val top = (32f - cameraY) * zoom
            val bottom = (64f - cameraY) * zoom
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(left, top)
            GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(right, top)
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(right, bottom)
            GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(left, bottom)
            GL11.glEnd()
        }
        GL11.glFinish()
        val pixels: ByteBuffer = BufferUtils.createByteBuffer(64 * 64 * 4)
        GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        return IntArray(pixels.remaining()) { index -> pixels.get(index).toInt() and 0xff }
    }

    private fun configureProjection(renderScale: Float) {
        GL11.glViewport(0, 0, 64, 64)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        val logicalExtent = 64.0 / renderScale
        GL11.glOrtho(0.0, logicalExtent, 0.0, logicalExtent, -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
    }

    private fun pixel(pixels: IntArray, x: Int, y: Int): IntArray {
        val offset = (y * 64 + x) * 4
        return intArrayOf(pixels[offset], pixels[offset + 1], pixels[offset + 2], pixels[offset + 3])
    }

    private fun writeGlPreview(pixels: IntArray) {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val rgba = pixel(pixels, x, y)
                val argb = (rgba[3] shl 24) or (rgba[0] shl 16) or (rgba[1] shl 8) or rgba[2]
                image.setRGB(x, 63 - y, argb)
            }
        }
        val output = Path.of("build/ui-regression/world-water-shader-gl.png")
        Files.createDirectories(output.parent)
        ImageIO.write(image, "png", output.toFile())
    }

    /** Real game artwork, rendered by the production GL shadow program rather than a mock filter. */
    private fun writeNativeFactoryShadowComparison(shadowProgram: Int) {
        val asset = Path.of("D:/steam/steamapps/common/Rusted Warfare/res/drawable/land_factory.png")
        if (!Files.isRegularFile(asset)) return
        val sprite = ImageIO.read(asset.toFile())
        val buffer = BufferUtils.createByteBuffer(sprite.width * sprite.height * 4)
        for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
            val argb = sprite.getRGB(x, y)
            buffer.put((argb ushr 16).toByte()).put((argb ushr 8).toByte()).put(argb.toByte())
                .put((argb ushr 24).toByte())
        }
        buffer.flip()
        val texture = GL11.glGenTextures()
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                sprite.width,
                sprite.height,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                buffer
            )
            fun quad(left: Float, top: Float, right: Float, bottom: Float) {
                GL11.glBegin(GL11.GL_QUADS)
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(left, top)
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(right, top)
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(right, bottom)
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(left, bottom)
                GL11.glEnd()
            }

            fun frame(enhanced: Boolean): IntArray {
                configureProjection(1f)
                GL11.glClearColor(0.72f, 0.78f, 0.82f, 1f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                GL11.glEnable(GL11.GL_TEXTURE_2D)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
                if (enhanced) {
                    GL20.glUseProgram(shadowProgram)
                    GL20.glUniform1i(uniform(shadowProgram, "u_texture"), 0)
                    GL20.glUniform4f(uniform(shadowProgram, "u_shadowUv"), 0f, 0f, 1f, 1f)
                    GL20.glUniform2f(uniform(shadowProgram, "u_shadowTexel"), 1f / sprite.width, 1f / sprite.height)
                    GL11.glColor4f(0f, 0f, 0f, 96f / 255)
                    quad(18f, 16f, 57f, 54f)
                }
                GL20.glUseProgram(0)
                GL11.glColor4f(1f, 1f, 1f, 1f)
                quad(12f, 9f, 48f, 53f)
                GL11.glFinish()
                val rgba = BufferUtils.createByteBuffer(64 * 64 * 4)
                GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba)
                return IntArray(rgba.remaining()) { rgba.get(it).toInt() and 0xff }
            }

            val original = frame(false)
            val enhanced = frame(true)
            assertTrue(original.indices.count { original[it] != enhanced[it] } > 80,
                "Projected shadow must visibly change the factory scene")
            val output = BufferedImage(128, 80, BufferedImage.TYPE_INT_ARGB)
            for ((panel, rgba) in listOf(original, enhanced).withIndex()) for (y in 0 until 64) for (x in 0 until 64) {
                val p = pixel(rgba, x, y)
                output.setRGB(panel * 64 + x, 16 + 63 - y, (255 shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2])
            }
            val graphics = output.createGraphics()
            try {
                graphics.color = java.awt.Color.WHITE
                graphics.font = java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 10)
                graphics.drawString("原始", 5, 12); graphics.drawString("增强", 69, 12)
            } finally {
                graphics.dispose()
            }
            val path = Path.of("build/ui-regression/native-factory-shadow-comparison.png")
            Files.createDirectories(path.parent)
            ImageIO.write(output, "png", path.toFile())
        } finally {
            GL11.glDeleteTextures(texture)
        }
    }

    private fun locateLwjglNativeDirectory(): Path? {
        val roots = buildList {
            System.getProperty("rwpp.lwjgl.nativePath")?.let { add(Path.of(it)) }
            System.getenv("RWPP_LWJGL_NATIVE_PATH")?.let { add(Path.of(it)) }
            System.getenv("ProgramFiles(x86)")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("ProgramFiles")?.let { add(Path.of(it).resolve("Steam")) }
            System.getenv("STEAM_ROOT")?.let { add(Path.of(it)) }
            add(Path.of("D:/Steam"))
            add(Path.of("D:/steam"))
        }
        for (root in roots.distinct()) {
            val direct = listOf(
                root,
                root.resolve("steamapps/common/Rusted Warfare"),
                root.resolve("steamapps/common/rusted_warfare")
            )
            direct.firstOrNull { Files.isRegularFile(it.resolve("lwjgl64.dll")) }?.let { return it }
            val libraries = root.resolve("steamapps/libraryfolders.vdf")
            if (Files.isRegularFile(libraries)) {
                val contents = runCatching { Files.readString(libraries) }.getOrDefault("")
                Regex("\\\"path\\\"\\s*\\\"([^\\\"]+)\\\"").findAll(contents).forEach { match ->
                    val library = Path.of(match.groupValues[1].replace("\\\\", "/"))
                    val game = library.resolve("steamapps/common/Rusted Warfare")
                    if (Files.isRegularFile(game.resolve("lwjgl64.dll"))) return game
                }
            }
        }
        return null
    }

    private fun restoreNativePath(previous: String?) {
        if (previous == null) System.clearProperty("org.lwjgl.librarypath")
        else System.setProperty("org.lwjgl.librarypath", previous)
    }

    private companion object {
        /** Exact Steam assets/shaders/plain.vert source used by PaintGLShader for shadows. */
        val NATIVE_PLAIN_VERTEX_SHADER = """
            #version 130

            varying vec4 v_color;
            varying vec2 v_texCoords;

            void main() {
                gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
                v_color = gl_Color;
                v_texCoords = vec2(gl_MultiTexCoord0);
            }
        """.trimIndent()
    }

}
