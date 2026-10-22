/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.sound.Sounds
import io.github.rwpp.ui.UI

@Composable
fun AeroMapPreview(
    map: GameMap?,
    showImage: Boolean,
) {
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
        if (map != null && map !is NetworkMap && showImage) {
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
                    clickSound = Sounds.openPopup,
                    onClick = { UI.inspectingMap = map }
                )
            }
        } else {
            Text(
                text = "NO PREVIEW AVAILABLE",
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
    maxScale: Float = 6f
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(newOffset: Offset, currentScale: Float): Offset {
        val maxOffsetX = (containerSize.width * (currentScale - 1f)) / 2f
        val maxOffsetY = (containerSize.height * (currentScale - 1f)) / 2f
        return Offset(
            x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
            y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            offset = clampOffset(Offset.Zero, 3f)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val targetScale = (scale * zoom).coerceIn(minScale, maxScale)
                    offset = if (targetScale > 1f) {
                        clampOffset(offset + pan, targetScale)
                    } else {
                        Offset.Zero
                    }
                    scale = targetScale
                }
            },
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
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )

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

            if (scale > 1.05f) {
                Text(
                    text = "[RESET]",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable {
                        scale = 1f
                        offset = Offset.Zero
                    }
                )
            }
        }
    }
}

@Composable
fun MapInspectorDialog(
    visible: Boolean,
    map: GameMap?,
    onDismissRequest: () -> Unit
) {
    if (!visible || map == null) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                        tag = "HIGH-RES TACTICAL VIEW",
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Close),
                        size = 32.dp,
                        iconSize = 16.dp,
                        isDanger = true,
                        clickSound = Sounds.closePopup,
                        onClick = onDismissRequest
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}