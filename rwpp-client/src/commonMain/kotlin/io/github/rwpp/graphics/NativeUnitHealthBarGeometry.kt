/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Geometry from Unit.drawOverlay: collision radius, altitude-adjusted center and native zoom. */
internal fun nativeUnitHealthBarBounds(
    center: Offset, radius: Float, zoom: Float, sx: Float, sy: Float,
    drawnScaledDown: Boolean, mini: Boolean,
): Rect {
    val barScale = if (!drawnScaledDown && zoom < 1f) 1f else zoom
    val halfWidth = radius * barScale * sx
    val top = center.y + (radius + 4f) * zoom * sy
    return Rect(
        center.x - halfWidth, top, center.x + halfWidth,
        top + (if (mini) 1f else 4f) * barScale * sy
    )
}
