/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2


import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import android.graphics.RenderEffect as AndroidRenderEffect

private const val GLITCH_SHADER_SOURCE = """
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

actual fun Modifier.skiaGlitchEffect(
    progress: Float,
    time: Float
): Modifier {
    if (Build.VERSION.SDK_INT < 33) {
        return drawWithContent {
            drawContent()

            val width = size.width
            val height = size.height
            if (progress <= 0.001f || progress >= 0.999f || width <= 0f || height <= 0f) {
                return@drawWithContent
            }
            drawLegacyGlitchOverlay(width, height, progress, time)
        }
    }

    return graphicsLayer {
        val width = size.width
        val height = size.height
        if (progress <= 0.001f || progress >= 0.999f || width <= 0f || height <= 0f) {
            renderEffect = null
        } else {
            val shader = RuntimeShader(GLITCH_SHADER_SOURCE)
            shader.setFloatUniform("iResolution", width, height)
            shader.setFloatUniform("iProgress", progress)
            shader.setFloatUniform("iTime", time)
            renderEffect = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLegacyGlitchOverlay(
    width: Float,
    height: Float,
    progress: Float,
    time: Float
) {
    val intensity = sin(progress * PI).toFloat().coerceIn(0f, 1f).let { it * it }
    val random = Random((time * 20f).toInt())
    repeat((6 * intensity).toInt()) { index ->
        val stripeY = random.nextFloat() * height
        val stripeWidth = width * (0.12f + random.nextFloat() * 0.34f) * intensity
        val stripeX = random.nextFloat() * (width - stripeWidth).coerceAtLeast(0f)
        drawRect(
            color = if (index % 2 == 0) Color(0xFF00E5FF) else Color.White,
            topLeft = Offset(stripeX, stripeY),
            size = Size(stripeWidth, 2f + random.nextFloat() * 5f),
            alpha = 0.55f * intensity
        )
    }

    var y = 0f
    while (y < height) {
        drawRect(
            color = Color.Black,
            topLeft = Offset(0f, y),
            size = Size(width, 1f),
            alpha = 0.035f * intensity
        )
        y += 8f
    }
}