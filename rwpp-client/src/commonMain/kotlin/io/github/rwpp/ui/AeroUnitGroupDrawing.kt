/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.graphics.AeroUnitGroupVisual
import io.github.rwpp.graphics.AeroSelectionOverlayFrame
import io.github.rwpp.graphics.WorldVisuals
import io.github.rwpp.graphics.aeroUnitGroupColors

private val groupNumberStyle = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp
)

private data class PlacedGroupNumber(val group: Int, val bounds: Rect)

/** Stationary boxes and numbers are retained in the same texture as the regular HUD. */
@Composable
internal fun AeroSelectionOverlayCanvas(
    modifier: Modifier, snapshot: WorldVisuals, presentation: State<AeroSelectionOverlayFrame?>?, accent: Color,
) {
    val text = rememberTextMeasurer(cacheSize = aeroUnitGroupColors.size)
    val layouts = remember(text) { arrayOfNulls<TextLayoutResult>(aeroUnitGroupColors.size) }
    Canvas(modifier.fillMaxSize()) {
        val frame = presentation?.value ?: if (presentation == null) {
            AeroSelectionOverlayFrame(snapshot.unitGroups, snapshot.camera, snapshot.zoom, snapshot.width, snapshot.height,
                selectionBounds = snapshot.bulkSelectionBounds)
        } else return@Canvas
        if (frame.selectionBounds == null && frame.groups.isEmpty() ||
            frame.width <= 0f || frame.height <= 0f || frame.zoom <= 0f) return@Canvas
        val sx = size.width / frame.width
        val sy = size.height / frame.height
        clipRect(right = (frame.gameAreaWidth * sx).coerceIn(0f, size.width)) {
            fun project(point: Offset) =
                Offset((point.x - frame.camera.x) * frame.zoom * sx, (point.y - frame.camera.y) * frame.zoom * sy)
            frame.selectionBounds?.let {
                val box = Rect(project(it.topLeft), project(it.bottomRight))
                if (box.width.isFinite() && box.height.isFinite() && box.overlaps(Rect(Offset.Zero, size))) {
                    drawRect(accent.copy(alpha = 0.82f), box.topLeft, box.size, style = Stroke(1.5.dp.toPx()))
                }
            }
            drawAeroUnitGroups(frame.groups, text, layouts, ::project)
        }
    }
}

/** One compact number per frame. Overlapping groups stack their badges instead of hiding each other. */
private fun DrawScope.drawAeroUnitGroups(
    groups: List<AeroUnitGroupVisual>,
    text: TextMeasurer,
    numberLayouts: Array<TextLayoutResult?>,
    project: (Offset) -> Offset,
) {
    if (groups.isEmpty() || size.width <= 0f || size.height <= 0f) return
    val viewport = Rect(Offset.Zero, size)
    val labelSpacing = 8.dp.toPx()
    val placed = ArrayList<PlacedGroupNumber>()
    val corners = Path()
    clipRect {
        for (group in groups) {
            val bounds = Rect(project(group.bounds.topLeft), project(group.bounds.bottomRight))
                .inflate((group.number - 1) * 1.5.dp.toPx())
            if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
                !bounds.right.isFinite() || !bounds.bottom.isFinite() || !viewport.overlaps(bounds)
            ) continue
            val slot = group.number - 1
            val accent = aeroUnitGroupColors[slot]
            val layout = numberLayouts[slot] ?: text.measure(group.number.toString(), style = groupNumberStyle, maxLines = 1)
                .also { numberLayouts[slot] = it }
            val labelSize = Size(maxOf(19.dp.toPx(), layout.size.width + 8.dp.toPx()), layout.size.height + 2.dp.toPx())
            val preferred = Offset(
                (bounds.center.x - labelSize.width / 2f).coerceIn(0f, (size.width - labelSize.width).coerceAtLeast(0f)),
                (bounds.top - labelSize.height).coerceIn(0f, (size.height - labelSize.height).coerceAtLeast(0f)),
            )
            val spacing = Rect(preferred, labelSize).inflate(labelSpacing)
            if (placed.any { it.group == group.number && it.bounds.overlaps(spacing) }) continue
            var label = Rect(preferred, labelSize)
            val step = labelSize.height + 3.dp.toPx()
            for (lane in 0 until aeroUnitGroupColors.size * 2) {
                if (placed.none { it.bounds.overlaps(label) }) break
                val shift = (lane / 2 + 1) * step * if (lane % 2 == 0) -1f else 1f
                val top = (preferred.y + shift).coerceIn(0f, (size.height - labelSize.height).coerceAtLeast(0f))
                label = Rect(Offset(preferred.x, top), labelSize)
            }
            if (placed.any { it.bounds.overlaps(label) }) continue
            placed += PlacedGroupNumber(group.number, label)
            val labelOrigin = label.topLeft

            drawRect(accent.copy(alpha = 0.22f), bounds.topLeft, bounds.size, style = Stroke(1.dp.toPx()))
            val leg = minOf(9.dp.toPx(), bounds.width / 2f, bounds.height / 2f)
            val left = bounds.left
            val top = bounds.top
            val right = bounds.right
            val bottom = bounds.bottom
            corners.reset()
            corners.moveTo(left, top + leg)
            corners.lineTo(left, top)
            corners.lineTo(left + leg, top)
            corners.moveTo(right - leg, top)
            corners.lineTo(right, top)
            corners.lineTo(right, top + leg)
            corners.moveTo(right, bottom - leg)
            corners.lineTo(right, bottom)
            corners.lineTo(right - leg, bottom)
            corners.moveTo(left + leg, bottom)
            corners.lineTo(left, bottom)
            corners.lineTo(left, bottom - leg)
            drawPath(corners, Color.Black.copy(alpha = 0.7f), style = Stroke(3.dp.toPx()))
            drawPath(corners, accent.copy(alpha = 0.86f), style = Stroke(1.4.dp.toPx()))
            val corner = CornerRadius(3.dp.toPx())
            drawRoundRect(Color(0xe6141a25), labelOrigin, labelSize, corner)
            drawRoundRect(accent.copy(alpha = 0.7f), labelOrigin, labelSize, corner, style = Stroke(1.dp.toPx()))
            drawText(
                layout, color = accent,
                topLeft = label.center - Offset(layout.size.width / 2f, layout.size.height / 2f)
            )
        }
    }
}
