/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2.scrollbar.foundation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.v2.AeroSciFiTheme
import io.github.rwpp.widget.v2.scrollbar.ScrollbarLayoutSide
import io.github.rwpp.widget.v2.scrollbar.TestTagsScrollbar

@Composable
internal fun VerticalScrollbarLayout(
    thumbSizeNormalized: Float,
    thumbOffsetNormalized: Float,
    thumbIsInAction: Boolean,
    thumbIsSelected: Boolean,
    settings: ScrollbarLayoutSettings,
    draggableModifier: Modifier,
    indicator: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = rememberScrollbarLayoutState(
        thumbIsInAction = thumbIsInAction,
        thumbIsSelected = thumbIsSelected,
        settings = settings,
    )

    val isHighlighted = thumbIsInAction || thumbIsSelected

    val animBorderColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = 0.4f
        ),
        animationSpec = tween(120),
        label = "vThumbBorder"
    )

    val animCoreColor by animateColorAsState(
        targetValue = if (isHighlighted) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        animationSpec = tween(120),
        label = "vThumbCore"
    )

    val indicatorShape = remember { AeroSciFiTheme.ChamferAeroShape(6f) }

    Layout(
        modifier = modifier.testTag(TestTagsScrollbar.container),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxHeight(thumbSizeNormalized)
                    .padding(
                        start = if (settings.side == ScrollbarLayoutSide.Start) settings.scrollbarPadding else 0.dp,
                        end = if (settings.side == ScrollbarLayoutSide.End) settings.scrollbarPadding else 0.dp,
                    )
                    .alpha(state.hideAlpha.value)
                    .clip(settings.thumbShape)
                    .width(settings.thumbThickness)
                    .background(state.thumbColor.value)
                    .border(1.dp, animBorderColor, settings.thumbShape)
                    .testTag(TestTagsScrollbar.scrollbarThumb),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight(0.45f)
                        .background(animCoreColor, RoundedCornerShape(1.dp))
                )
            }

            // 1: HUD 悬浮 Indicator 指示器
            when (indicator) {
                null -> Box(Modifier)
                else -> Surface(
                    shape = indicatorShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .testTag(TestTagsScrollbar.scrollbarIndicator)
                        .alpha(state.hideAlpha.value)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        indicator()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(settings.scrollbarPadding * 2 + settings.thumbThickness)
                    .run { if (state.activeDraggableModifier.value) then(draggableModifier) else this }
                    .testTag(TestTagsScrollbar.scrollbarContainer)
            )
        },
        measurePolicy = { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }

            layout(constraints.minWidth, constraints.minHeight) {
                val placeableThumb = placeables[0]
                val placeableIndicator = placeables[1]
                val placeableScrollbarArea = placeables[2]

                val offset = (constraints.maxHeight.toFloat() * thumbOffsetNormalized).toInt()

                val hideDisplacementPx = when (settings.side) {
                    ScrollbarLayoutSide.Start -> -state.hideDisplacement.value.roundToPx()
                    ScrollbarLayoutSide.End -> +state.hideDisplacement.value.roundToPx()
                }

                placeableThumb.placeRelative(
                    x = when (settings.side) {
                        ScrollbarLayoutSide.Start -> 0
                        ScrollbarLayoutSide.End -> constraints.maxWidth - placeableThumb.width
                    } + hideDisplacementPx,
                    y = offset
                )

                placeableIndicator.placeRelative(
                    x = when (settings.side) {
                        ScrollbarLayoutSide.Start -> 0 + placeableThumb.width + 4
                        ScrollbarLayoutSide.End -> constraints.maxWidth - placeableThumb.width - placeableIndicator.width - 4
                    } + hideDisplacementPx,
                    y = offset + placeableThumb.height / 2 - placeableIndicator.height / 2
                )
                placeableScrollbarArea.placeRelative(
                    x = when (settings.side) {
                        ScrollbarLayoutSide.Start -> 0
                        ScrollbarLayoutSide.End -> constraints.maxWidth - placeableScrollbarArea.width
                    },
                    y = 0
                )
            }
        }
    )
}