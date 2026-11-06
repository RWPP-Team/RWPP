/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal actual fun DrawScope.beginAeroRangeFrame(phase: Float) = Unit

internal actual fun releaseAeroRangeResources() = Unit

/**
 * Range masks are drawn as ordinary vector geometry.
 *
 * The previous implementation pushed a saveLayer on the compose canvas' native Skia canvas and
 * pushed the layer through a RuntimeShader image filter. Compose keeps its own save/restore state on
 * that same canvas, so the layer checkpoint could restore Compose's state instead of the layer. The
 * leaked layer kept compositing the previous mask, and every newly selected building added another
 * tinted rectangle. Drawing the union directly removes the layer entirely.
 */
internal actual fun DrawScope.drawAeroRangeGroupMask(
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    worldPerPixel: Offset,
    phase: Float,
    detailed: Boolean,
) {
    if (group.circles.isEmpty()) return
    val viewportWidth = size.width
    val viewportHeight = size.height
    if (viewportWidth <= 0f || viewportHeight <= 0f) return
    var union: Path? = null
    for (circle in group.circles) {
        val x = (circle.center.x - camera.x) * worldToScreen.x
        val y = (circle.center.y - camera.y) * worldToScreen.y
        val rx = circle.radius * worldToScreen.x
        val ry = circle.radius * worldToScreen.y
        if (!x.isFinite() || !y.isFinite() || !rx.isFinite() || !ry.isFinite() || rx <= 0f || ry <= 0f) continue
        val left = x - rx
        val top = y - ry
        val right = x + rx
        val bottom = y + ry
        if (right < 0f || bottom < 0f || left > viewportWidth || top > viewportHeight) continue
        val oval = Path().apply { addOval(Rect(left, top, right, bottom)) }
        val previous = union
        union = if (previous == null) oval else Path().apply {
            op(previous, oval, PathOperation.Union)
        }
    }
    val merged = union ?: return
    // One filled union keeps overlaps from darkening each other and keeps a single outline,
    // matching the native mask composite that this replaced.
    drawPath(merged, group.color.copy(alpha = 0.025f))
    val outlineAlpha = group.color.alpha * if (detailed) 0.8f else 0.76f
    drawPath(
        merged,
        group.color.copy(alpha = outlineAlpha.coerceIn(0f, 1f)),
        style = Stroke(width = if (detailed) 1.4.dp.toPx() else 1.dp.toPx()),
    )
}
