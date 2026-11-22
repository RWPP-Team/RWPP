/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.map.MapPreviewGenerationService
import io.github.rwpp.game.team
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.sound.Sounds
import io.github.rwpp.ui.BattleRoomScreen
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.color.getTeamColor

@Composable
fun AeroMapPreview(
    map: GameMap?,
    showImage: Boolean,
) {
    // The Coil fetcher renders a preview from the TMX when no PNG ships beside the
    // map, so a joined multiplayer room is shown even before its network path exists.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(AeroSciFiTheme.ChamferAeroShape(10f))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                AeroSciFiTheme.ChamferAeroShape(10f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (map != null && showImage) {
            AeroZoomableImage(
                model = map,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                AeroIconButton(
                    painter = rememberVectorPainter(Icons.Default.Search),
                    size = 26.dp,
                    iconSize = 14.dp,
                    contentDescription = readI18n("rwpp.map.inspect"),
                    clickSound = Sounds.openPopup,
                    onClick = { UI.inspectingMap = map }
                )
            }
        } else {
            Text(
                text = readI18n("rwpp.map.noPreviewAvailable"),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun AeroZoomableImage(
    model: Any?,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 6f,
    wheelZoom: Boolean = false,
) {
    var scale by remember(model) { mutableFloatStateOf(minScale) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(model) { mutableStateOf(DrawSize.Zero) }
    val fitted = fitPreviewImage(containerSize, imageSize)
    val positions by produceState(emptyList(), model) {
        value = emptyList()
        if (model is GameMap) value = MapPreviewGenerationService().spawnPositions(model)
    }

    fun clampOffset(newOffset: Offset, currentScale: Float): Offset {
        val maxOffsetX = ((fitted.width * currentScale - containerSize.width) / 2f).coerceAtLeast(0f)
        val maxOffsetY = ((fitted.height * currentScale - containerSize.height) / 2f).coerceAtLeast(0f)
        return Offset(
            x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
            y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
        )
    }

    fun zoomAt(centroid: Offset, pan: Offset, zoom: Float) {
        val target = (scale * zoom).coerceIn(minScale, maxScale)
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        offset = clampOffset(offset + pan + (centroid - center - offset) * (1f - target / scale), target)
        scale = target
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(model, minScale, maxScale, fitted, containerSize) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        if (scale > minScale) {
                            scale = minScale
                            offset = Offset.Zero
                        } else {
                            zoomAt(point, Offset.Zero, 3f)
                        }
                    }
                )
            }
            .pointerInput(model, minScale, maxScale, fitted, containerSize) {
                detectTransformGestures { centroid, pan, zoom, _ -> zoomAt(centroid, pan, zoom) }
            }
            .then(if (!wheelZoom) Modifier else Modifier.pointerInput(model, minScale, maxScale, fitted, containerSize) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val pointer = event.changes.firstOrNull() ?: continue
                        val delta = pointer.scrollDelta.y
                        if (pointer.isConsumed || delta == 0f) continue
                        val targetScale = if (delta < 0f) 1.25f else 0.8f
                        zoomAt(pointer.position, Offset.Zero, targetScale)
                        pointer.consume()
                    }
                }
            }),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(model)
                .size(Size.ORIGINAL) // 【关键修正】强制加载原始全分辨率像素，避免被 Coil 降采样裁切
                .crossfade(true)
                .build(),
            contentDescription = null,
            filterQuality = FilterQuality.High, // 缩放采样插值算法
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                imageSize = DrawSize(state.result.image.width.toFloat(), state.result.image.height.toFloat())
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )

        val navigator = UI.liveNavigator
        if (positions.isNotEmpty() && navigator?.current is BattleRoomScreen && fitted.width > 0f && fitted.height > 0f) {
            val textMeasurer = rememberTextMeasurer(cacheSize = positions.size * 2)
            val markerTextStyle = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (wheelZoom) 12.sp else 10.sp,
            )
            Canvas(Modifier.matchParentSize()) {
                val zoomedWidth = fitted.width * scale
                val zoomedHeight = fitted.height * scale
                val topLeft = center + offset - Offset(zoomedWidth / 2f, zoomedHeight / 2f)
                for ((slot, xFraction, yFraction) in positions) {
                    val point = topLeft + Offset(xFraction * zoomedWidth, yFraction * zoomedHeight)
                    val label = textMeasurer.measure((slot + 1).toString(), markerTextStyle)
                    val radius = maxOf((if (wheelZoom) 11.dp else 8.dp).toPx(), label.size.width / 2f + 3.dp.toPx())
                    drawCircle(Color(0xE6101822), radius, point)
                    drawCircle(getTeamColor(slot), radius, point, style = Stroke(1.5.dp.toPx()))
                    drawText(label, topLeft = point - Offset(label.size.width / 2f, label.size.height / 2f))
                    if (scale >= 3f || maxScale >= 8f) {
                        (navigator.current as? BattleRoomScreen)?.update // 观察是否更新
                        val player = Team.getTeam(slot) ?: continue

                        val annotatedText = buildAnnotatedString {
                            withStyle(
                                SpanStyle(color = getTeamColor(player.team))
                            ) {
                                append("[${player.teamLetter}]")
                            }

                            append(" ${player.name}")
                        }

                        val nameLayout = textMeasurer.measure(
                            text = annotatedText,
                            style = markerTextStyle
                        )

                        drawText(
                            textLayoutResult = nameLayout,
                            topLeft = Offset(
                                point.x - nameLayout.size.width / 2f,
                                point.y + radius + 4.dp.toPx()
                            )
                        )
                    }
                }
            }
        }

        // 状态面板 (显示当前放大倍率 & 一键重置)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${(scale * 100).toInt()}%",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (scale > minScale + 0.05f) {
                Text(
                    text = "[${readI18n("rwpp.common.reset").uppercase()}]",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        scale = minScale
                        offset = Offset.Zero
                    }
                )
            }
        }
    }
}

internal fun fitPreviewImage(container: IntSize, image: DrawSize): DrawSize {
    if (container.width <= 0 || container.height <= 0 || image.width <= 0f || image.height <= 0f) return DrawSize.Zero
    val fit = minOf(container.width / image.width, container.height / image.height)
    return DrawSize(image.width * fit, image.height * fit)
}

@Composable
fun MapInspectorDialog(
    visible: Boolean,
    map: GameMap?,
    onDismissRequest: () -> Unit
) {
    if (!visible || map == null) return

    AnimatedDialog(onDismissRequest = onDismissRequest) { dismiss ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            shape = AeroSciFiTheme.ChamferAeroShape(16f),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroHeader(
                        title = "INSPECT MAP: ${map.displayName().uppercase()}",
                        tag = "High-resolution tactical view".uppercase(),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Close),
                        size = 32.dp,
                        iconSize = 16.dp,
                        contentDescription = readI18n("rwpp.common.close"),
                        isDanger = true,
                        clickSound = Sounds.closePopup,
                        onClick = dismiss
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            AeroSciFiTheme.ChamferAeroShape(8f)
                        )
                ) {
                    AeroZoomableImage(
                        model = map,
                        maxScale = 8f,
                        wheelZoom = true,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
