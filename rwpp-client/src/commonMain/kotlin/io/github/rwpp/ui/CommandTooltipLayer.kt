/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.logic.GameVisualSettings
import io.github.rwpp.widget.v2.AeroPanel

internal fun commandTooltipScale(): Float =
    GameVisualSettings.get()?.commandTooltipScale?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f

@Composable
internal fun CommandTooltipLayer(
    topCentered: Boolean = false,
    placementTooltip: CommandTooltip? = null,
) {
    val settings = GameVisualSettings.get()
    val scale = commandTooltipScale()
    DisposableEffect(Unit) { onDispose { CommandHover.clear() } }

    val showQueue = topCentered && HudQueuePopupTouch.active
    val placement = placementTooltip.takeUnless { showQueue }
    var retainedPlacement by remember { mutableStateOf<CommandTooltip?>(null) }
    if (placement != null) SideEffect { retainedPlacement = placement }
    val placementAlpha by animateFloatAsState(
        if (placement != null) 1f else 0f,
        tween(if (settings?.hudAnimations == false) 0 else 180),
    )
    if (showQueue) return
    val hover = CommandHover.current.value
    val tooltip = placement ?: hover ?: retainedPlacement?.takeIf { placementAlpha > 0f } ?: return
    val isPlacement = placement != null || hover == null
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { coordinates = it }) {
        val edge = 8.dp
        val attached = coordinates?.takeIf { it.isAttached }
        fun local(point: Offset): Offset =
            attached?.localPositionOf(attached.findRootCoordinates(), point) ?: point
        val anchorLeft = local(tooltip.anchor.topLeft)
        val anchorRight = local(tooltip.anchor.topRight)
        val pointer = Offset(tooltip.anchor.left, tooltip.pointerY ?: tooltip.anchor.top)
        val pointerY = with(density) { local(pointer).y.toDp() }
        val leftEdge = with(density) { anchorLeft.x.toDp() }
        val right = with(density) { anchorRight.x.toDp() }
        val leftRoom = leftEdge - edge
        val rightRoom = maxWidth - edge - right
        val sideRoom = maxOf(leftRoom, rightRoom)
        val hasAnchor = !topCentered && tooltip.anchor != Rect.Zero
        val fitsBeside = hasAnchor && sideRoom >= minOf(120.dp, 160.dp * scale)
        val widthLimit = if (fitsBeside) sideRoom else maxWidth - edge * 2
        val width = (310.dp * scale).coerceAtMost(widthLimit.coerceAtLeast(1.dp))
        val farEdge = (maxWidth - width - edge).coerceAtLeast(edge)
        val x = when {
            !fitsBeside -> (maxWidth - width) / 2
            leftRoom >= width -> (leftEdge - width).coerceIn(edge, farEdge)
            else -> right.coerceIn(edge, farEdge)
        }
        val below = (pointerY + 24.dp).coerceIn(edge, (maxHeight - edge).coerceAtLeast(edge))
        val above = (pointerY - 24.dp).coerceIn(edge, (maxHeight - edge).coerceAtLeast(edge))
        val spaceBelow = maxHeight - edge - below
        val useBelow = spaceBelow >= above - edge
        val height = when {
            topCentered -> minOf(maxHeight * 0.45f, maxHeight - edge * 2)
            fitsBeside || !hasAnchor -> maxHeight - edge * 2
            useBelow -> spaceBelow
            else -> above - edge
        }.coerceAtLeast(1.dp)
        var cardHeight by remember { mutableStateOf(0.dp) }
        val y = when {
            topCentered -> edge
            fitsBeside || !hasAnchor -> pointerY - 20.dp * scale
            useBelow -> below
            else -> above - cardHeight
        }.coerceIn(edge, (maxHeight - cardHeight - edge).coerceAtLeast(edge))
        val placementMotion = if (isPlacement) Modifier.graphicsLayer {
            alpha = placementAlpha
            translationY = (1f - placementAlpha) * -8.dp.toPx()
        } else Modifier
        AeroPanel(
            Modifier.absoluteOffset(x, y).width(width).heightIn(max = height)
                .then(placementMotion)
                .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp * scale),
                verticalArrangement = Arrangement.spacedBy(6.dp * scale),
            ) {
                Text(
                    tooltip.title, color = MaterialTheme.colorScheme.primary, fontSize = (13f * scale).sp,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tooltip.text.isNotBlank()) {
                    Text(tooltip.text, fontSize = (11f * scale).sp, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
