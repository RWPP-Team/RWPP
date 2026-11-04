/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.skiaPaint
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

object SkiaGlitchShader {
    private const val SKSL_SRC = """
        uniform shader content;
        uniform float2 iResolution;
        uniform float iProgress;
        uniform float iTime;

        float hash(float2 p) {
            p = fract(p * float2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        half4 main(float2 fragCoord) {
            float glitchFactor = sin(iProgress * 3.14159265);
            glitchFactor = pow(glitchFactor, 1.8);

            if (glitchFactor < 0.001) {
                return content.eval(fragCoord);
            }

            float blockHeight = iResolution.y * 0.02;
            float blockY = floor(fragCoord.y / blockHeight);
            float noiseVal = hash(float2(blockY, floor(iTime * 20.0)));

            float offsetX = 0.0;
            if (noiseVal < glitchFactor * 0.7) {
                offsetX = (hash(float2(blockY, iTime)) - 0.5) * iResolution.x * 0.12 * glitchFactor;
            }

            float2 shiftedCoord = fragCoord + float2(offsetX, 0.0);
            shiftedCoord = clamp(shiftedCoord, float2(0.0), iResolution);

            float rgbShift = iResolution.x * 0.02 * glitchFactor * noiseVal;
            half4 colorCenter = content.eval(shiftedCoord);
            half4 colorLeft = content.eval(clamp(shiftedCoord - float2(rgbShift, 0.0), float2(0.0), iResolution));
            half4 colorRight = content.eval(clamp(shiftedCoord + float2(rgbShift, 0.0), float2(0.0), iResolution));

            half4 finalColor = half4(colorLeft.r, colorCenter.g, colorRight.b, colorCenter.a);
            float scanline = sin(fragCoord.y * 1.2) * 0.08 * glitchFactor;
            finalColor.rgb -= scanline;

            if (noiseVal > 0.8 && glitchFactor > 0.25) {
                finalColor.rgb += half3(0.0, 0.85, 1.0) * 0.35 * glitchFactor;
            }

            return finalColor;
        }
    """

    val runtimeEffect: RuntimeEffect by lazy {
        RuntimeEffect.makeForShader(SKSL_SRC)
    }
}

actual fun Modifier.skiaGlitchEffect(
    progress: Float,
    time: Float
): Modifier = drawWithContent {
    val width = size.width
    val height = size.height

    if (progress <= 0.001f || progress >= 0.999f || width <= 0f || height <= 0f) {
        drawContent()
        return@drawWithContent
    }

    val skiaCanvas = drawContext.canvas.nativeCanvas
    val builder = RuntimeShaderBuilder(SkiaGlitchShader.runtimeEffect)
    builder.uniform("iResolution", width, height)
    builder.uniform("iProgress", progress)
    builder.uniform("iTime", time)

    val imageFilter = ImageFilter.makeRuntimeShader(
        runtimeShaderBuilder = builder,
        shaderName = "content",
        input = null
    )

    val paint = Paint().skiaPaint.apply {
        this.imageFilter = imageFilter
    }

    val checkpoint = skiaCanvas.saveLayer(null, paint)
    drawContent()
    skiaCanvas.restoreToCount(checkpoint)
}