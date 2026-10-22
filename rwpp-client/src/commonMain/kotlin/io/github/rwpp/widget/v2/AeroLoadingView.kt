/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.api.coroutines.GameMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AeroLoadingView(
    visible: Boolean,
    onLoaded: () -> Unit,
    enableAnimation: Boolean = true,
    cancellable: Boolean = false,
    loadContent: suspend LoadingContext.() -> Boolean?
) {
    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onLoaded,
        enableDismiss = cancellable
    ) { dismiss ->
        var cancel by remember { mutableStateOf(false) }
        var loadingMessage by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val result = loadContent(LoadingContext { loadingMessage = it })
                withContext(GameMainDispatcher) {
                    if (result == true) {
                        loadingMessage = ""
                        dismiss()
                    } else if (result == false) {
                        cancel = true
                    }
                }
            }
        }

        AeroPanel(
            modifier = Modifier
                .width(460.dp)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header 栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroHeader(
                        title = if (cancel) "PROCESS ABORTED" else "EXECUTING TASK",
                        tag = if (cancel) "ERR.HALT" else "SYS.BUSY",
                        color = if (cancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )

                    if (cancellable || cancel) {
                        Spacer(modifier = Modifier.width(12.dp))
                        AeroIconButton(
                            painter = rememberVectorPainter(Icons.Default.Close),
                            size = 28.dp,
                            iconSize = 14.dp,
                            isDanger = true,
                            onClick = dismiss
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                AeroDivider(
                    color = if (cancel) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(28.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (enableAnimation && !cancel) {
                        AeroSciFiLoader(
                            size = 110.dp,
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                    } else if (cancel) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Text(
                        text = loadingMessage.ifEmpty {
                            if (cancel) "OPERATION TERMINATED BY SYSTEM" else "PROCESSING DATA STREAM..."
                        },
                        color = if (cancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (cancel) "[ STATUS: 0x404_ABORT ]" else "[ STATUS: RUNNING ]",
                        color = (if (cancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun AeroSciFiLoader(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AeroLoaderTransition")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerRot"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerRot"
    )

    val sweepRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepRot"
    )

    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corePulse"
    )

    Canvas(
        modifier = modifier.size(size)
    ) {
        val centerPx = Offset(this.size.width / 2f, this.size.height / 2f)
        val maxRadius = minOf(this.size.width, this.size.height) / 2f - 4.dp.toPx()

        rotate(outerRotation, pivot = centerPx) {
            drawArc(
                color = accentColor.copy(alpha = 0.5f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(maxRadius * 0.25f, maxRadius * 0.12f)
                    )
                )
            )
        }

        rotate(sweepRotation, pivot = centerPx) {
            val radarRadius = maxRadius * 0.85f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        accentColor.copy(alpha = 0.05f),
                        accentColor.copy(alpha = 0.45f)
                    ),
                    center = centerPx
                ),
                startAngle = 0f,
                sweepAngle = 110f,
                useCenter = true,
                topLeft = Offset(centerPx.x - radarRadius, centerPx.y - radarRadius),
                size = Size(radarRadius * 2f, radarRadius * 2f)
            )
        }

        val innerRadius = maxRadius * 0.62f
        rotate(innerRotation, pivot = centerPx) {
            drawArc(
                color = accentColor.copy(alpha = 0.85f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(5.dp.toPx(), 8.dp.toPx())
                    )
                ),
                topLeft = Offset(centerPx.x - innerRadius, centerPx.y - innerRadius),
                size = Size(innerRadius * 2f, innerRadius * 2f)
            )
        }

        val crosshairSize = maxRadius * 0.22f
        drawLine(
            color = accentColor.copy(alpha = corePulse),
            start = Offset(centerPx.x - crosshairSize, centerPx.y),
            end = Offset(centerPx.x + crosshairSize, centerPx.y),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = accentColor.copy(alpha = corePulse),
            start = Offset(centerPx.x, centerPx.y - crosshairSize),
            end = Offset(centerPx.x, centerPx.y + crosshairSize),
            strokeWidth = 1.5.dp.toPx()
        )

        drawCircle(
            color = accentColor.copy(alpha = corePulse),
            radius = 3.dp.toPx(),
            center = centerPx
        )

        val bracketOffset = maxRadius * 0.96f
        val bracketLen = 7.dp.toPx()
        val cornerColor = accentColor.copy(alpha = 0.35f)

        val corners = listOf(
            Offset(-bracketOffset, -bracketOffset),
            Offset(bracketOffset, -bracketOffset),
            Offset(-bracketOffset, bracketOffset),
            Offset(bracketOffset, bracketOffset)
        )

        for (offset in corners) {
            val cornerPos = centerPx + offset
            val dirX = if (offset.x > 0) -1f else 1f
            val dirY = if (offset.y > 0) -1f else 1f

            drawLine(
                color = cornerColor,
                start = cornerPos,
                end = cornerPos + Offset(bracketLen * dirX, 0f),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = cornerColor,
                start = cornerPos,
                end = cornerPos + Offset(0f, bracketLen * dirY),
                strokeWidth = 1.5.dp.toPx()
            )
        }
    }
}