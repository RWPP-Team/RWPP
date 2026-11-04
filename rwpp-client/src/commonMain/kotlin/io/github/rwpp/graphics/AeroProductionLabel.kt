/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

internal fun productionTimeLabel(
    seconds: Int?,
    paused: Boolean,
    pausedLabel: String,
    waitingLabel: String,
    waiting: Boolean = false
): String {
    val time = seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "—"
    return when {
        paused -> "$pausedLabel · $time"
        waiting -> "$waitingLabel · $time"
        else -> time
    }
}

internal fun DrawScope.drawAeroProductionLabel(
    item: AeroProductionVisual, center: Offset,
    radius: Float, text: TextMeasurer, pausedLabel: String, waitingLabel: String
) {
    val pad = 5.dp.toPx()
    val iconSize = if (item.icon != null) 24.dp.toPx() else 0f
    val name = text.measure(
        item.name, TextStyle(color = Color(0xffedf7fc), fontSize = 11.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = (170.dp.toPx() - iconSize).toInt().coerceAtLeast(1))
    )
    val details = text.measure(
        "${
            productionTimeLabel(
                item.remainingSeconds,
                item.paused,
                pausedLabel,
                waitingLabel,
                item.waiting
            )
        }   ×${item.count}",
        TextStyle(color = if (item.paused || item.waiting) Color(0xffffcb74) else Color(0xff8bdcf0), fontSize = 10.sp)
    )
    val width = max(name.size.width, details.size.width).toFloat() + iconSize + pad * 3
    val height = max(iconSize, (name.size.height + details.size.height).toFloat()) + pad * 2
    val origin = Offset(
        (center.x - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f)),
        (center.y - radius - height - 7.dp.toPx()).coerceAtLeast(2.dp.toPx())
    )
    val cut = 5.dp.toPx()
    val outline = Path().apply {
        moveTo(origin.x + cut, origin.y); lineTo(origin.x + width, origin.y)
        lineTo(origin.x + width, origin.y + height - cut); lineTo(origin.x + width - cut, origin.y + height)
        lineTo(origin.x, origin.y + height); lineTo(origin.x, origin.y + cut); close()
    }
    drawPath(outline, Color(0xdd13232d))
    drawPath(outline, lerp(item.color, Color.White, 0.45f).copy(alpha = 0.65f), style = Stroke(0.7.dp.toPx()))
    item.icon?.let {
        drawImage(
            it, dstOffset = androidx.compose.ui.unit.IntOffset((origin.x + pad).toInt(), (origin.y + pad).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(iconSize.toInt(), iconSize.toInt())
        )
    }
    val content = origin + Offset(iconSize + pad * 1.5f, pad)
    drawText(name, topLeft = content)
    drawText(details, topLeft = content + Offset(0f, name.size.height.toFloat()))
    drawRect(
        Color(0xff78d4ea), origin + Offset(cut, height - 2.dp.toPx()),
        Size((width - cut * 2) * item.progress.coerceIn(0f, 1f), 1.dp.toPx())
    )
}
