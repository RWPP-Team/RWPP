/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.utils

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

internal fun hudTooltipPosition(anchor: IntRect, window: IntSize, content: IntSize, gap: Int): IntOffset {
    val left = anchor.left - content.width - gap
    val x = if (left >= 0) left else anchor.right + gap
    return IntOffset(
        x.coerceIn(0, (window.width - content.width).coerceAtLeast(0)),
        anchor.top.coerceIn(0, (window.height - content.height).coerceAtLeast(0))
    )
}

