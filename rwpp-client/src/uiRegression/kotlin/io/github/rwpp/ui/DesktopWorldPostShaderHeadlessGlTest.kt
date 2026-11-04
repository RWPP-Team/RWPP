package io.github.rwpp.ui

import io.github.rwpp.graphics.DesktopWorldPostShaderEnhancements
import io.github.rwpp.graphics.WorldPostShaderSources
import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Compiles and exercises the production desktop post shader in an offscreen LWJGL context. */
class DesktopWorldPostShaderHeadlessGlTest {
    @Test
    fun postShaderEnhancesMidtoneContrastAndPreservesHueAndWhite() {
        val nativeDir = locateLwjglNativeDirectory()
        Assume.assumeTrue("Steam lwjgl64.dll was not found; offscreen native GL is unavailable", nativeDir != null)

        val previousNativePath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val pbuffer = try {
            Pbuffer(32, 32, PixelFormat().withAlphaBits(8).withDepthBits(0), null, null, ContextAttribs(3, 0))
        } catch (failure: Throwable) {
            restoreNativePath(previousNativePath)
            Assume.assumeTrue("LWJGL could not create an OpenGL pbuffer: ${failure.javaClass.simpleName}", false)
            return
        }

        var vertex = 0
        var productionFragment = 0
        var baselineFragment = 0
        var productionProgram = 0
        var baselineProgram = 0
        var texture = 0
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

            vertex = compile(GL20.GL_VERTEX_SHADER, NATIVE_PLAIN_VERTEX_SHADER)
            productionFragment = compile(GL20.GL_FRAGMENT_SHADER, DesktopWorldPostShaderEnhancements.FRAGMENT_SHADER)
            baselineFragment = compile(GL20.GL_FRAGMENT_SHADER, BASELINE_FRAGMENT_SHADER)
            productionProgram = link(vertex, productionFragment)
            baselineProgram = link(vertex, baselineFragment)
            texture = GL11.glGenTextures()
            configureTexture(texture)

            uploadTexture(texture) { _, _ -> floatArrayOf(0.25f, 0.25f, 0.25f) }
            val darkBaseline = pixel(draw(baselineProgram, texture), 8, 16)
            val darkEnhanced = pixel(draw(productionProgram, texture), 8, 16)
            uploadTexture(texture) { _, _ -> floatArrayOf(0.75f, 0.75f, 0.75f) }
            val lightBaseline = pixel(draw(baselineProgram, texture), 8, 16)
            val lightEnhanced = pixel(draw(productionProgram, texture), 8, 16)
            assertTrue(darkEnhanced[0] < darkBaseline[0] - 2, "the S-curve should deepen dark midtones")
            assertTrue(lightEnhanced[0] > lightBaseline[0] + 2, "the S-curve should lift bright midtones")
            val strongSun = pixel(draw(productionProgram, texture, sunlight = 3f), 8, 16)
            assertTrue(
                strongSun[0] > lightEnhanced[0] + 8,
                "sunlight intensity must change final world/sprite brightness"
            )
            assertTrue(lightEnhanced.all { it <= 255 }, "bright midtones must remain within the framebuffer range")

            uploadTexture(texture) { _, _ -> floatArrayOf(0.52f, 0.21f, 0.12f) }
            val coloredBaseline = pixel(draw(baselineProgram, texture), 8, 16)
            val coloredEnhanced = pixel(draw(productionProgram, texture), 8, 16)
            val redScale = coloredEnhanced[0].toFloat() / coloredBaseline[0]
            val greenScale = coloredEnhanced[1].toFloat() / coloredBaseline[1]
            val blueScale = coloredEnhanced[2].toFloat() / coloredBaseline[2]
            assertTrue(
                abs(redScale - greenScale) < 0.025f && abs(greenScale - blueScale) < 0.04f,
                "the effect must scale RGB together and retain the input hue"
            )

            uploadTexture(texture) { _, _ -> floatArrayOf(1f, 1f, 1f) }
            val white = pixel(draw(productionProgram, texture), 8, 16)
            assertTrue(white.take(3).all { it >= 254 }, "white source pixels must remain white")

            uploadTexture(texture) { x, _ ->
                val value = if (x < 16) 0.88f else 0.94f
                floatArrayOf(value, value, value)
            }
            val snowDetail = draw(productionProgram, texture, sunlight = 3f)
            val snowDark = pixel(snowDetail, 8, 16)[0]
            val snowLight = pixel(snowDetail, 24, 16)[0]
            assertTrue(
                snowLight - snowDark >= 5 && snowLight < 254,
                "strong sunlight must preserve pale terrain detail instead of clipping both levels white: $snowDark/$snowLight"
            )

            uploadTexture(texture) { x, y ->
                if (x in 12..19 && y in 12..19) floatArrayOf(1f, 1f, 1f)
                else floatArrayOf(0f, 0f, 0f)
            }
            val highlight = draw(productionProgram, texture)
            val localLift = pixel(highlight, 10, 16)
            val distantBlack = pixel(highlight, 4, 4)
            assertTrue(
                localLift.take(3).all { it in 1..8 },
                "a nearby white highlight should create only a small neutral lift"
            )
            assertTrue(distantBlack.take(3).all { it == 0 }, "highlight response must stay local")
            verifySceneReflectionsAndWetGround(productionProgram, texture)
            verifyNativeDisplacementLighting(vertex, productionProgram, texture)
        } finally {
            if (texture != 0) GL11.glDeleteTextures(texture)
            if (baselineProgram != 0) GL20.glDeleteProgram(baselineProgram)
            if (productionProgram != 0) GL20.glDeleteProgram(productionProgram)
            if (baselineFragment != 0) GL20.glDeleteShader(baselineFragment)
            if (productionFragment != 0) GL20.glDeleteShader(productionFragment)
            if (vertex != 0) GL20.glDeleteShader(vertex)
            pbuffer.destroy()
            restoreNativePath(previousNativePath)
        }
    }

    private fun verifyNativeDisplacementLighting(vertex: Int, baseProgram: Int, scene: Int) {
        val fragment = compile(GL20.GL_FRAGMENT_SHADER, WorldPostShaderSources.DISPLACEMENT_FRAGMENT_SHADER)
        val program = link(vertex, fragment)
        val wave = GL11.glGenTextures()
        try {
            uploadTexture(scene) { x, y -> floatArrayOf(0.12f + x * 0.018f, 0.16f + y * 0.012f, 0.09f + x * 0.006f) }
            val lit = draw(baseProgram, scene)
            uploadTexture(wave) { _, _ -> floatArrayOf(128f / 255f, 128f / 255f, 1f) }
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, scene)
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL20.glUseProgram(program)
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "screenBase"), 1)
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "screenBaseSize"), SIZE.toFloat(), SIZE.toFloat())
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_resolution"), SIZE.toFloat(), SIZE.toFloat())
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_uiScaling"), 1f)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_offsetBy"), 0.5f)
            val neutral = draw(program, wave)
            for (y in 4..27) for (x in 4..27) for (c in 0..2) {
                assertTrue(
                    abs(neutral[(y * SIZE + x) * 4 + c] - lit[((SIZE - 1 - y) * SIZE + x) * 4 + c]) <= 1,
                    "Neutral native wave must keep enhanced lighting; x=$x y=$y channel=$c"
                )
            }
            uploadTexture(wave) { _, _ -> floatArrayOf(160f / 255f, 128f / 255f, 1f) }
            val shifted = draw(program, wave)
            var changed = 0
            for (y in 4..27) for (x in 4..25) for (c in 0..2) {
                assertTrue(
                    abs(shifted[(y * SIZE + x) * 4 + c] - lit[((SIZE - 1 - y) * SIZE + x + 2) * 4 + c]) <= 1,
                    "Native displacement must shift the lit scene, preserving its exposure"
                )
                if (abs(shifted[(y * SIZE + x) * 4 + c] - neutral[(y * SIZE + x) * 4 + c]) > 2) changed++
            }
            assertTrue(changed > 100, "Wave must retain its visible native displacement")
        } finally {
            GL20.glUseProgram(0); GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glDeleteTextures(wave); GL20.glDeleteProgram(program); GL20.glDeleteShader(fragment)
        }
    }

    private fun verifySceneReflectionsAndWetGround(program: Int, scene: Int) {
        val material = GL11.glGenTextures()
        try {
            uploadTexture(scene) { x, y ->
                when {
                    x in 6..15 && y in 6..15 -> floatArrayOf(0.72f, 0.82f, 0.32f)
                    x >= 16 && y >= 16 -> floatArrayOf(0.12f, 0.22f, 0.30f)
                    else -> floatArrayOf(0.18f, 0.29f, 0.12f)
                }
            }
            uploadTexture(material) { x, y ->
                when {
                    x in 6..15 && y in 6..15 -> floatArrayOf(0f, 0.5f, 1f)
                    x >= 16 && y >= 16 -> floatArrayOf(1f, 0.9f, 0f)
                    x >= 16 && y < 16 -> floatArrayOf(0f, 0.49f, 0f)
                    else -> floatArrayOf(0f, 0f, 0f)
                }
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, material)
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL20.glUseProgram(program)
            fun scalar(name: String, value: Float) {
                val location = GL20.glGetUniformLocation(program, name)
                assertTrue(location >= 0, "scene shader must expose $name")
                GL20.glUniform1f(location, value)
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "u_sceneMaterials"), 1)
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_materialUvScale"), 1f, 1f)
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_worldPerUv"), 64f, 64f)
            GL20.glUniform2f(GL20.glGetUniformLocation(program, "u_sceneExtent"), 1f, 1f)
            scalar("u_materialPass", 1f)
            scalar("u_surfaceHighlights", 0f); scalar("u_dynamicBloom", 0f)
            scalar("u_wetStrength", 1f); scalar("u_postSSR", 0f); scalar("u_postTraceSteps", 6f)
            val off = draw(program, scene)
            scalar("u_postSSR", 1f); scalar("u_postTraceSteps", 2f)
            val basic = draw(program, scene)
            scalar("u_postTraceSteps", 6f)
            val traced = draw(program, scene)
            scalar("u_wetStrength", 0f)
            val noWet = draw(program, scene)
            fun changed(a: IntArray, b: IntArray, xs: IntRange, ys: IntRange): Int =
                ys.sumOf { y -> xs.count { x -> (0..2).any { c -> kotlin.math.abs(a[(y * SIZE + x) * 4 + c] - b[(y * SIZE + x) * 4 + c]) >= 2 } } }

            val waterHits = changed(off, traced, 16..30, 16..30)
            val wetHits = changed(noWet, traced, 16..30, 2..15)
            val qualityHits = changed(basic, traced, 16..30, 16..30)
            assertTrue(waterHits > 20, "screen-space water reflections must include the bright unit: pixels=$waterHits")
            assertTrue(wetHits > 10, "wet-ground strength must affect scene reflections: pixels=$wetHits")
            assertTrue(
                qualityHits > 10,
                "ray tracing samples must affect water reflection quality: pixels=$qualityHits"
            )
            scalar("u_postSSR", 0f); scalar("u_surfaceHighlights", 1f); scalar("u_dynamicBloom", 1f)
            val withHighlights = draw(program, scene)
            val spriteChanges = changed(off, withHighlights, 7..14, 7..14)
            assertTrue(
                spriteChanges == 0,
                "sprite pixels must ignore ground highlights and bloom: pixels=$spriteChanges"
            )
            val sprite = pixel(withHighlights, 10, 10)
            val expected = listOf((0.72f * 255).toInt(), (0.82f * 255).toInt(), (0.32f * 255).toInt())
            assertTrue(
                (0..2).all { abs(sprite[it] - expected[it]) <= 1 },
                "unit material coverage must preserve original sprite RGB"
            )
            val output = java.nio.file.Path.of("build/ui-regression/scene-reflection-metrics.txt")
            java.nio.file.Files.createDirectories(output.parent)
            java.nio.file.Files.writeString(
                output,
                "water reflection pixels=$waterHits; wet-ground pixels=$wetHits; ray quality pixels=$qualityHits; unwanted sprite lighting pixels=$spriteChanges"
            )
        } finally {
            GL20.glUseProgram(program)
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_materialPass"), 0f)
            GL11.glDeleteTextures(material); GL13.glActiveTexture(GL13.GL_TEXTURE0)
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            val log = GL20.glGetShaderInfoLog(shader, 4096)
            GL20.glDeleteShader(shader)
            throw AssertionError("desktop post shader compile failed: $log")
        }
        return shader
    }

    private fun link(vertex: Int, fragment: Int): Int {
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        GL20.glLinkProgram(program)
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            val log = GL20.glGetProgramInfoLog(program, 4096)
            GL20.glDeleteProgram(program)
            throw AssertionError("desktop post shader link failed: $log")
        }
        return program
    }

    private fun configureTexture(texture: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP)
    }

    private fun uploadTexture(texture: Int, colorAt: (Int, Int) -> FloatArray) {
        configureTexture(texture)
        val pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val color = colorAt(x, y)
            pixels.put((color[0].coerceIn(0f, 1f) * 255f).toInt().toByte())
            pixels.put((color[1].coerceIn(0f, 1f) * 255f).toInt().toByte())
            pixels.put((color[2].coerceIn(0f, 1f) * 255f).toInt().toByte())
            pixels.put(0xff.toByte())
        }
        pixels.flip()
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels
        )
    }

    private fun draw(program: Int, texture: Int, sunlight: Float = 1f): IntArray {
        GL11.glViewport(0, 0, SIZE, SIZE)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, SIZE.toDouble(), 0.0, SIZE.toDouble(), -1.0, 1.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL20.glUseProgram(program)
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "u_texture"), 0)
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_sunStrength"), sunlight)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(SIZE.toFloat(), 0f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(SIZE.toFloat(), SIZE.toFloat())
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, SIZE.toFloat())
        GL11.glEnd()
        GL11.glFinish()
        val pixels: ByteBuffer = BufferUtils.createByteBuffer(SIZE * SIZE * 4)
        GL11.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        return IntArray(pixels.remaining()) { pixels.get(it).toInt() and 0xff }
    }

    private fun pixel(pixels: IntArray, x: Int, y: Int) = pixels.slice((y * SIZE + x) * 4 until (y * SIZE + x) * 4 + 4)

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
                    val game = Path.of(match.groupValues[1].replace("\\\\", "/"))
                        .resolve("steamapps/common/Rusted Warfare")
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
        const val SIZE = 32
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
        val BASELINE_FRAGMENT_SHADER = """
            #version 130
            precision mediump float;
            varying vec4 v_color;
            varying vec2 v_texCoords;
            uniform sampler2D u_texture;
            void main() {
                gl_FragColor = texture2D(u_texture, v_texCoords) * v_color;
            }
        """.trimIndent()
    }
}
