/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

/** One program per stage. Backends adapt sampler/coordinate syntax, never the lighting equations. */
internal object WorldShaderDialect {
    fun sksl(glsl: String, terrainMask: Boolean = false, backgroundMask: Boolean = false): String {
        val samplers = Regex("uniform\\s+sampler2D\\s+(\\w+)\\s*;").findAll(glsl).map { it.groupValues[1] }.toList()
        var source = glsl.lineSequence().filterNot {
            val line = it.trimStart()
            line.startsWith("#version") || line.startsWith("precision ") || line.startsWith("varying ")
        }.joinToString("\n")
        source = source.replace(Regex("\\bvec([234])\\b"), "float$1").replace(Regex("\\bsampler2D\\b"), "shader")
        for (name in samplers) source = source
            .replace(Regex("texture2D\\(\\s*${name}\\s*,"), "rwppSample_$name(")
            .replace(Regex("textureSize\\(\\s*${name}\\s*,\\s*0\\s*\\)"), "rwppSize_$name")
        source = source.replace("fwidth(world)", "rwppWorldPerPixel").replace("fwidth(p)", "rwppWorldPerPixel")
            .replace(Regex("gl_FragColor\\s*=\\s*([^;]+);")) { "return rwppOutput(${it.groupValues[1]});" }
            .replace(Regex("\\breturn\\s*;"), "")
        val main = "half4 main(float2 rwppPixel) {\nfloat2 v_texCoords=rwppPixel/rwppSize_u_texture;\n" +
                "float2 v_worldPosition=rwppWorldOrigin+rwppPixel*rwppWorldPerPixel;\nfloat4 v_color=float4(1.0);"
        check(source.contains("void main() {")) { "Missing canonical fragment entry point" }
        source = source.replace("void main() {", main)
        val uniforms = "uniform float2 rwppWorldOrigin;\nuniform float2 rwppWorldPerPixel;\n" +
                samplers.joinToString("\n") { "uniform float2 rwppSize_$it;" }
        val helpers = samplers.joinToString("\n") { name ->
            val sample = "$name.eval(uv*rwppSize_$name)"
            if (terrainMask && name == "u_terrainMask" || backgroundMask && name == "u_texture") """
                float4 rwppSample_$name(float2 uv) {
                    float4 encoded=$sample;
                    float bits=floor(encoded.b*255.0+0.5);
                    float cliff=floor(bits/64.0);
                    float lava=mod(bits,64.0)/63.0;
                    return float4(encoded.rg,mix(1.0,38.0/255.0,cliff),128.0/255.0+lava*127.0/255.0);
                }
            """.trimIndent() else "float4 rwppSample_$name(float2 uv) { float4 c=$sample; return float4(c.rgb/max(c.a,0.00001),c.a); }"
        }
        for (name in samplers) source = source.replace("uniform shader $name;", "")
        val output = "half4 rwppOutput(float4 c) { return half4(c.rgb*c.a,c.a); }"
        return uniforms + "\n" + samplers.joinToString("\n") { "uniform shader $it;" } + "\n" + helpers + "\n" + output + "\n" + source
    }
}
