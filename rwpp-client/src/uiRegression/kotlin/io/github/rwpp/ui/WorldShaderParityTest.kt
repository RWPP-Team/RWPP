package io.github.rwpp.ui

import io.github.rwpp.graphics.*

import org.jetbrains.skia.*
import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes the same production programs on native GL and SkSL with equal material/scene inputs. */
class WorldShaderParityTest : DesktopComposeGraphicsTest() {
    private val w = 96;
    private val h = 64
    private val numbers = mapOf(
        "u_time" to floatArrayOf(7f),
        "u_cacheScale" to floatArrayOf(1f),
        "u_lightStrength" to floatArrayOf(1f),
        "u_waveStrength" to floatArrayOf(1f),
        "u_wetStrength" to floatArrayOf(1f),
        "u_terrainHighlights" to floatArrayOf(1f),
        "u_maskWorldOrigin" to floatArrayOf(0f, 0f),
        "u_maskWorldScale" to floatArrayOf(1f / w, 1f / h),
        "u_maskExtent" to floatArrayOf(1f, 1f),
        "u_maskTexel" to floatArrayOf(1f / w, 1f / h),
        "u_sourceTexel" to floatArrayOf(1f / w, 1f / h),
        "u_sunStrength" to floatArrayOf(1f),
        "u_materialPass" to floatArrayOf(1f),
        "u_postSSR" to floatArrayOf(1f),
        "u_postTraceSteps" to floatArrayOf(6f),
        "u_surfaceHighlights" to floatArrayOf(1f),
        "u_dynamicBloom" to floatArrayOf(1f),
        "u_materialUvScale" to floatArrayOf(1f, 1f),
        "u_spriteUvScale" to floatArrayOf(1f, 1f),
        "u_worldPerUv" to floatArrayOf(w.toFloat(), h.toFloat()),
        "u_sceneExtent" to floatArrayOf(1f, 1f),
        "u_shadowUv" to floatArrayOf(0f, 0f, 1f, 1f),
        "u_shadowTexel" to floatArrayOf(1f / w, 1f / h),
        "u_shadowSoft" to floatArrayOf(0.4f),
        "u_worldOrigin" to floatArrayOf(0f, 0f),
        "u_viewWorldSize" to floatArrayOf(w.toFloat(), h.toFloat())
    )

    private fun coverage(x: Int, y: Int) = x in 64..75 && y in 22..38

    @Test
    fun viewportMaterialProjectionDoesNotDependOnMaskTextureDimensions() {
        val size = 8
        val pixels =
            IntArray(size * size) { i -> worldSceneMaterialPixel(if (i % size >= 4) 1f else 0f, 0f, 0f, false) }
        RuntimeEffect.makeForShader(WorldCanvasShaderSources.BACKGROUND).use { effect ->
            RuntimeShaderBuilder(effect).use { builder ->
                skiaImageFromArgb(pixels, size, size).use { image ->
                    image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT).use { input ->
                        builder.child("u_texture", input)
                        builder.uniform("rwppSize_u_texture", size.toFloat(), size.toFloat())
                        builder.uniform("rwppWorldOrigin", 100f, 50f); builder.uniform("rwppWorldPerPixel", 1f, 1f)
                        builder.uniform("u_worldOrigin", 100f, 50f)
                        builder.uniform("u_viewWorldSize", 8f, 8f)
                        builder.uniform("u_maskWorldOrigin", 100f, 50f)
                        builder.uniform("u_maskWorldScale", 1f / 40f, 1f / 40f)
                        builder.uniform("u_maskExtent", 1f, 1f); builder.uniform("u_maskTexel", 1f / size, 1f / size)
                        builder.makeShader().use { shader ->
                            Surface.makeRasterN32Premul(w, h).use { surface ->
                                Paint().use { paint ->
                                    paint.shader = shader; surface.canvas.drawRect(
                                    Rect.makeWH(
                                        w.toFloat(),
                                        h.toFloat()
                                    ), paint
                                )
                                }
                                surface.makeImageSnapshot().use { output ->
                                    Bitmap.makeFromImage(output).use { bitmap ->
                                        assertEquals(0, bitmap.getColor(2, 5) ushr 16 and 255)
                                        assertEquals(255, bitmap.getColor(26, 5) ushr 16 and 255)
                                        assertEquals(255, bitmap.getColor(w - 1, 5) ushr 16 and 255)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun source(alpha: Boolean) = IntArray(w * h) { i ->
        val x = i % w;
        val y = i / w;
        val a = if (alpha && (x !in 14..80 || y !in 10..52)) 0 else 255
        (a shl 24) or ((58 + x % 19 * 3) shl 16) or ((72 + y % 11 * 4) shl 8) or (52 + (x + y) % 13 * 3)
    }

    @Test
    fun terrainWaterShadowsSpritesAndFullScenePostMatchAcrossDialects() {
        val native = Path.of("D:/steam/steamapps/common/Rusted Warfare")
        Assume.assumeTrue("LWJGL native library is required", java.nio.file.Files.exists(native.resolve("lwjgl64.dll")))
        System.setProperty("org.lwjgl.librarypath", native.toString())
        val context = Pbuffer(w, h, PixelFormat().withAlphaBits(8), null, null, ContextAttribs(3, 0))
        try {
            context.makeCurrent()
            val stages = listOf(
                Triple("terrain", WorldTerrainShaderSources.FRAGMENT_SHADER, WorldCanvasShaderSources.TERRAIN),
                Triple("post", WorldPostShaderSources.FRAGMENT_SHADER, WorldCanvasShaderSources.POST),
                Triple("shadow", WorldShadowShaderSources.FRAGMENT_SHADER, WorldCanvasShaderSources.SHADOW),
                Triple("unit-mask", WorldUnitShaderSources.FRAGMENT_SHADER, WorldCanvasShaderSources.UNIT_MASK),
                Triple(
                    "background",
                    WorldUnitShaderSources.BACKGROUND_FRAGMENT_SHADER,
                    WorldCanvasShaderSources.BACKGROUND
                )
            )
            val measurements = ArrayList<String>()
            for ((stage, glsl, sksl) in stages) {
                val pixels = source(stage == "shadow" || stage == "unit-mask")
                val desktop = inputs(stage, pixels, false)
                val android = inputs(stage, pixels, true)
                val gl = renderGl(glsl, desktop)
                val sk = renderSkia(sksl, android, stage == "post")
                var error = 0L;
                var samples = 0;
                var maximum = 0
                for (y in 4 until h - 4) for (x in 4 until w - 4) {
                    val i = y * w + x
                    for (shift in intArrayOf(0, 8, 16, 24)) {
                        if (shift != 24 && ((gl[i] ushr 24) < 16 || (sk[i] ushr 24) < 16)) continue
                        val d = abs((gl[i] ushr shift and 255) - (sk[i] ushr shift and 255))
                        error += d; samples++; maximum = maxOf(maximum, d)
                    }
                }
                val average = error.toDouble() / samples
                measurements.add("\"$stage\":{\"meanChannelError\":$average,\"maxChannelError\":$maximum}")
                assertTrue(average < 3.0 && maximum < 40, "$stage differs: average=$average max=$maximum")
                if (stage == "post") {
                    val i = 30 * w + 68
                    for (shift in intArrayOf(0, 8, 16)) assertTrue(
                        abs((sk[i] ushr shift and 255) - (pixels[i] ushr shift and 255)) <= 1,
                        "sprite over water must retain its original color"
                    )
                }
            }
            val directory = Path.of("build/ui-regression")
            java.nio.file.Files.createDirectories(directory)
            java.nio.file.Files.writeString(
                directory.resolve("world-shader-parity.json"),
                measurements.joinToString(",\n", "{\n", "\n}")
            )
        } finally {
            context.destroy()
        }
    }

    private fun inputs(stage: String, pixels: IntArray, android: Boolean): Map<String, IntArray> {
        val material = IntArray(w * h) { i ->
            val x = i % w;
            val y = i / w;
            val water = if (x >= w / 2) 1f else 0f
            val coast = ((x - w / 2) / 12f).coerceIn(-6f, 6f)
            if (stage == "post") {
                val unit = !android && coverage(x, y)
                val r = if (unit) 0 else if (water > 0) 255 else 0
                val g = if (unit) 128 else ((coast / 12f + 0.5f) * 255).toInt()
                -0x1000000 or (r shl 16) or (g shl 8) or if (unit) 255 else 0
            } else {
                val cliff = x < 12;
                val lava = if (x > 76 && y > 46) 1f else 0f
                if (android) worldSceneMaterialPixel(water, coast, lava, cliff)
                else terrainMaterialMaskPixel(water, coast, lava, cliff)
            }
        }
        val result = linkedMapOf("u_texture" to if (stage == "background") material else pixels)
        if (stage == "terrain") result["u_terrainMask"] = material
        if (stage == "post") {
            result["u_sceneMaterials"] = material
            result["u_spriteCoverage"] = IntArray(w * h) { i -> if (coverage(i % w, i / w)) -1 else 0 }
        }
        return result
    }

    private fun renderSkia(source: String, inputs: Map<String, IntArray>, separate: Boolean): IntArray {
        RuntimeEffect.makeForShader(source).use { effect ->
            RuntimeShaderBuilder(effect).use { builder ->
                val images = inputs.mapValues { skiaImageFromArgb(it.value, w, h) }
                val children = images.mapValues {
                    it.value.makeShader(
                        FilterTileMode.CLAMP,
                        FilterTileMode.CLAMP,
                        SamplingMode.DEFAULT
                    )
                }
                try {
                    children.forEach { (name, child) ->
                        builder.child(name, child); builder.uniform(
                        "rwppSize_$name",
                        w.toFloat(),
                        h.toFloat()
                    )
                    }
                    builder.uniform("rwppWorldOrigin", 0f, 0f); builder.uniform("rwppWorldPerPixel", 1f, 1f)
                    for ((name, values) in numbers) if (Regex("uniform\\s+float[234]?\\s+$name\\s*;").containsMatchIn(
                            source
                        )
                    ) builder.uniform(name, values)
                    if (source.contains("uniform float u_separateSpriteCoverage;")) builder.uniform(
                        "u_separateSpriteCoverage",
                        if (separate) 1f else 0f
                    )
                    builder.makeShader().use { shader ->
                        Surface.makeRasterN32Premul(w, h).use { surface ->
                            Paint().use { paint ->
                                paint.shader = shader; surface.canvas.drawRect(
                                Rect.makeWH(w.toFloat(), h.toFloat()),
                                paint
                            )
                            }
                            surface.makeImageSnapshot().use { image ->
                                Bitmap.makeFromImage(image).use { bitmap ->
                                    return IntArray(w * h) { i -> bitmap.getColor(i % w, i / w) }
                                }
                            }
                        }
                    }
                } finally {
                    children.values.forEach { it.close() }; images.values.forEach { it.close() }
                }
            }
        }
    }

    private fun compile(type: Int, text: String): Int {
        val shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, text); GL20.glCompileShader(shader)
        assertEquals(
            GL11.GL_TRUE,
            GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS),
            GL20.glGetShaderInfoLog(shader, 8192)
        )
        return shader
    }

    private fun renderGl(source: String, inputs: Map<String, IntArray>): IntArray {
        val vertex = compile(
            GL20.GL_VERTEX_SHADER, """
            #version 130
            varying vec4 v_color;varying vec2 v_texCoords;varying vec2 v_worldPosition;
            void main() { gl_Position=gl_ProjectionMatrix*gl_ModelViewMatrix*gl_Vertex;
              v_color=vec4(1.0);v_texCoords=gl_MultiTexCoord0.xy;v_worldPosition=v_texCoords*vec2(96.0,64.0); }
        """.trimIndent()
        )
        val fragment = compile(GL20.GL_FRAGMENT_SHADER, source)
        val program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(
            program,
            fragment
        ); GL20.glLinkProgram(program)
        assertEquals(
            GL11.GL_TRUE,
            GL20.glGetProgrami(program, GL20.GL_LINK_STATUS),
            GL20.glGetProgramInfoLog(program, 8192)
        )
        val textures = ArrayList<Int>()
        try {
            GL11.glViewport(0, 0, w, h); GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity(); GL11.glOrtho(
                0.0,
                w.toDouble(),
                h.toDouble(),
                0.0,
                -1.0,
                1.0
            )
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity(); GL20.glUseProgram(program)
            inputs.entries.forEachIndexed { unit, (name, pixels) ->
                val texture =
                    GL11.glGenTextures(); textures.add(texture); GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit); GL11.glBindTexture(
                GL11.GL_TEXTURE_2D,
                texture
            )
                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST
                ); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
                GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_WRAP_S,
                    GL12.GL_CLAMP_TO_EDGE
                ); GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
                val bytes = BufferUtils.createByteBuffer(w * h * 4)
                for (pixel in pixels) bytes.put((pixel ushr 16).toByte()).put((pixel ushr 8).toByte())
                    .put(pixel.toByte()).put((pixel ushr 24).toByte())
                bytes.flip(); GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                w,
                h,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                bytes
            )
                GL20.glUniform1i(GL20.glGetUniformLocation(program, name), unit)
            }
            for ((name, values) in numbers) {
                val location = GL20.glGetUniformLocation(program, name)
                when (values.size) {
                    1 -> GL20.glUniform1f(location, values[0]); 2 -> GL20.glUniform2f(
                    location,
                    values[0],
                    values[1]
                ); 4 -> GL20.glUniform4f(location, values[0], values[1], values[2], values[3])
                }
            }
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "u_separateSpriteCoverage"), 0f)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f); GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(
                w.toFloat(),
                0f
            )
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w.toFloat(), h.toFloat()); GL11.glTexCoord2f(
                0f,
                1f
            ); GL11.glVertex2f(0f, h.toFloat()); GL11.glEnd()
            val bytes = BufferUtils.createByteBuffer(w * h * 4); GL11.glReadPixels(
                0,
                0,
                w,
                h,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                bytes
            )
            return IntArray(w * h) { i ->
                val p = ((h - 1 - i / w) * w + i % w) * 4
                ((bytes.get(p + 3).toInt() and 255) shl 24) or ((bytes.get(p)
                    .toInt() and 255) shl 16) or ((bytes.get(p + 1).toInt() and 255) shl 8) or (bytes.get(p + 2)
                    .toInt() and 255)
            }
        } finally {
            GL20.glUseProgram(0); textures.forEach(GL11::glDeleteTextures); GL20.glDeleteProgram(program); GL20.glDeleteShader(
                vertex
            ); GL20.glDeleteShader(fragment)
        }
    }
}
