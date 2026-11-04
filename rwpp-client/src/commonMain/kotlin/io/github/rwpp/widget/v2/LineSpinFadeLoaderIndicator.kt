/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

enum class LinearAnimationType(val animDuration: Int, val circleDelay: Long) {
    CIRCULAR(500, 100L),
    SKIP_AND_REPEAT(250, 250L);
}

@Composable
fun LineSpinFadeLoaderIndicator(
    color: Color = Color.White,
    rectCount: Int = 8,
    linearAnimationType: LinearAnimationType = LinearAnimationType.CIRCULAR,
    penThickness: Float = 25f,
    radius: Float = 55f,
    elementHeight: Float = 20f,
    minAlpha: Float = 0.2f,
    maxAlpha: Float = 1.0f
) {

    val angleStep = 360f / rectCount
    val outerRadius = radius + elementHeight


// ------------------------ scale animation ---------------------
    val alphas = (1..rectCount).map { index ->
        var alpha: Float by remember { mutableStateOf(minAlpha) }
        LaunchedEffect(key1 = Unit) {

            when (linearAnimationType) {
                LinearAnimationType.CIRCULAR -> {
                    delay(linearAnimationType.circleDelay * index)
                }

                LinearAnimationType.SKIP_AND_REPEAT -> {
                    delay(linearAnimationType.circleDelay * index) // The constant value, here 250L, must be the same animation duration for this pattern to run
                }
            }

            animate(
                initialValue = minAlpha,
                targetValue = maxAlpha,
                animationSpec = infiniteRepeatable(
                    animation = when (linearAnimationType) {
                        LinearAnimationType.CIRCULAR -> {
                            tween(durationMillis = linearAnimationType.animDuration)
                        }

                        LinearAnimationType.SKIP_AND_REPEAT -> {
                            tween(durationMillis = linearAnimationType.animDuration)
                        }
                    },
                    repeatMode = RepeatMode.Reverse,
                )
            ) { value, _ -> alpha = value }
        }

        alpha
    }


// ----------------------------- UI --------------------------

    Canvas(modifier = Modifier) {

        val center = Offset(size.width / 2, size.height / 2)

        for (index in 0 until rectCount) {

            val angle = index * angleStep

            val startX =
                center.x + radius * cos(Math.toRadians(angle.toDouble())).toFloat()
            val startY =
                center.y + radius * sin(Math.toRadians(angle.toDouble())).toFloat()

            val endX = center.x + outerRadius * cos(Math.toRadians(angle.toDouble())).toFloat()
            val endY = center.y + outerRadius * sin(Math.toRadians(angle.toDouble())).toFloat()

            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = penThickness * alphas[index],
                alpha = alphas[index],
                cap = StrokeCap.Round,
            )
        }
    }
}