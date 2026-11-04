/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.aeroDamageLabel
import io.github.rwpp.game.replay.AeroReplayControlsState
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.graphics.*
import io.github.rwpp.logic.hud.GameUiPauseState
import org.koin.compose.koinInject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Passive overlay: Compose owns presentation state and drawing; native sampling stays in graphics. */
@Composable
internal fun GameScreen.AeroWorldEffects() {
    AeroOrderClickEffects()
    val settings = koinInject<Settings>()
    val accent = MaterialTheme.colorScheme.primary
    val productionPausedLabel = readI18n("rwpp.hud.production.paused")
    val productionWaitingLabel = readI18n("rwpp.hud.production.waiting")
    var snapshot by remember { mutableStateOf(WorldVisuals()) }
    val presentation = remember { AeroWorldPresentation() }
    DisposableEffect(presentation) {
        val registration = NativeWorldPresentation.register(presentation)
        onDispose { registration.close() }
    }
    val rangeSettings = remember { appKoin.get<EntityRangeUnitComp>() }
    val sampler = remember(presentation, rangeSettings) {
        AeroWorldEffectsSampler(rangeSettings, presentation)
    }
    OnGameUpdate {
        snapshot = sampler.sampleFrame(
            currentSnapshot = snapshot,
            settings = settings,
            selectedUnits = selectedUnits,
            accent = accent,
            productionPausedLabel = productionPausedLabel,
            productionWaitingLabel = productionWaitingLabel,
            hoverAllowed = !CommandPointer.inPanel && !GameUiPauseState.blocked && !isShowSendMessageDialog,
            showAllReplayOrders = AeroReplayControlsState.showAllUnitOrders
        )
    }
    AeroWorldEffectSurface(snapshot, presentation = presentation.frame)
}

@Composable
internal fun AeroWorldEffectSurface(
    snapshot: WorldVisuals, modifier: Modifier = Modifier,
    presentation: State<WorldVisuals?>? = null
) {
    val text = rememberTextMeasurer(cacheSize = 256)
    val accent = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    val damageStyle = remember(density) {
        TextStyle(
            color = Color(0xffffc18b), fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            shadow = androidx.compose.ui.graphics.Shadow(
                Color.Black.copy(alpha = 0.8f),
                Offset(0f, with(density) { 1.dp.toPx() }), with(density) { 2.dp.toPx() })
        )
    }
    Canvas(modifier.fillMaxSize()) {
        // Observe the native render-phase snapshot in draw, so HUD re-layout/recomposition
        // cannot pin world-space effects to an old update-phase camera while panning.
        val frame = presentation?.value ?: snapshot
        val sx = size.width / frame.width;
        val sy = size.height / frame.height
        fun project(point: Offset) = Offset(
            (point.x - frame.camera.x) * frame.zoom * sx,
            (point.y - frame.camera.y) * frame.zoom * sy
        )
        drawAeroRangeContours(
            frame.ranges, frame.camera, frame.zoom, frame.width, frame.height,
            frame.phase, frame.detailedRangeStyle
        )
        frame.orders.forEach { order ->
            val to = project(order.to)
            val margin = max(32.dp.toPx(), order.radius * frame.zoom * max(sx, sy))
            val viewport = Rect(-margin, -margin, size.width + margin, size.height + margin)
            val segment = clipAeroSegment(project(order.from), to, viewport) ?: return@forEach
            val from = segment.first
            val delta = segment.second - from;
            val length = delta.getDistance()
            val direction = if (length > 0.1f) delta / length else Offset(1f, 0f);
            val normal = Offset(-direction.y, direction.x)
            val color = when (order.kind) {
                AeroOrderKind.ATTACK -> Color(0xffff775c)
                AeroOrderKind.GUARD -> Color(0xffa3a3ff)
                AeroOrderKind.BUILD -> Color(0xff72d8f5)
                AeroOrderKind.REPAIR -> Color(0xff78e6a1)
                AeroOrderKind.RECLAIM -> Color(0xffffcb74)
                AeroOrderKind.RALLY -> Color(0xff73e4c0)
                else -> accent
            }.let { if (order.step > 0 || order.preview) it.copy(alpha = 0.65f) else it }
            val width = (1.5f + sqrt(order.count.toFloat())).coerceAtMost(9f).dp.toPx()
            drawLine(color.copy(alpha = 0.12f), from, segment.second, width * 3f)
            drawLine(color.copy(alpha = 0.35f), from, segment.second, 1.dp.toPx())
            val steps = if (length < 12f) 0 else (length / 32.dp.toPx()).toInt().coerceIn(1, 100)
            repeat(steps) { i ->
                val t = (i + frame.phase) / steps
                val point = from + delta * t
                val arrow = max(5.dp.toPx(), width * 1.5f)
                val path = Path().apply {
                    moveTo(
                        (point - direction * arrow + normal * arrow * 0.65f).x,
                        (point - direction * arrow + normal * arrow * 0.65f).y
                    )
                    lineTo(point.x, point.y)
                    lineTo(
                        (point - direction * arrow - normal * arrow * 0.65f).x,
                        (point - direction * arrow - normal * arrow * 0.65f).y
                    )
                }
                drawPath(path, color.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
            }
            val marker = 6.dp.toPx()
            if (viewport.contains(to)) when (order.kind) {
                AeroOrderKind.RALLY -> {
                    val path = Path().apply {
                        moveTo(to.x, to.y + marker); lineTo(to.x, to.y - marker * 1.6f)
                        lineTo(to.x + marker * 1.5f, to.y - marker * 0.8f); lineTo(to.x, to.y)
                    }
                    drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                    drawLine(color, to + Offset(-marker, marker), to + Offset(marker, marker), 1.dp.toPx())
                }

                AeroOrderKind.BUILD -> {
                    val r = (order.radius * frame.zoom * min(sx, sy)).coerceAtLeast(8.dp.toPx())
                    val extent = Size(r * 2f, r * 2f)
                    drawRect(color.copy(alpha = 0.08f), to - Offset(r, r), extent)
                    drawRect(
                        color, to - Offset(r, r), extent, style = Stroke(
                            1.3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 3.dp.toPx()),
                                frame.phase * 18.dp.toPx()
                            )
                        )
                    )
                    if (order.progress >= 0) drawRect(
                        color,
                        to + Offset(-r, r + 3.dp.toPx()),
                        Size(r * 2 * order.progress, 2.dp.toPx())
                    )
                }

                AeroOrderKind.GUARD -> {
                    val path = Path().apply {
                        moveTo(to.x - marker, to.y - marker); lineTo(to.x + marker, to.y - marker)
                        lineTo(to.x + marker * 0.8f, to.y + marker * 0.3f); lineTo(to.x, to.y + marker)
                        lineTo(to.x - marker * 0.8f, to.y + marker * 0.3f); close()
                    }
                    drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                }

                AeroOrderKind.REPAIR, AeroOrderKind.RECLAIM -> {
                    drawLine(color, to - Offset(marker, 0f), to + Offset(marker, 0f), 2.dp.toPx())
                    if (order.kind == AeroOrderKind.REPAIR) drawLine(
                        color,
                        to - Offset(0f, marker),
                        to + Offset(0f, marker),
                        2.dp.toPx()
                    )
                }

                else -> {
                    val path = Path().apply {
                        moveTo(to.x, to.y - marker); lineTo(to.x + marker, to.y)
                        lineTo(to.x, to.y + marker); lineTo(to.x - marker, to.y); close()
                    }
                    drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                }
            }
            if (viewport.contains(to) && (order.step > 0 || order.kind == AeroOrderKind.PATROL)) drawText(
                text, "${order.step + 1}",
                topLeft = to + Offset(marker + 3.dp.toPx(), -marker), style = TextStyle(color = color, fontSize = 10.sp)
            )
            if (order.count > 1) {
                val label = text.measure("×${order.count}", TextStyle(color = color, fontSize = 11.sp))
                val position = from + delta * 0.5f + normal * (width * 2f)
                drawRect(
                    Color.Black.copy(alpha = 0.65f), position - Offset(3.dp.toPx(), 1.dp.toPx()),
                    Size(label.size.width + 6.dp.toPx(), label.size.height + 2.dp.toPx())
                )
                drawText(label, topLeft = position)
            }
        }
        frame.damageNumbers.forEach { number ->
            val p = project(number.center) + Offset(number.lane * 9.dp.toPx(), -number.age * 32.dp.toPx())
            if (p.x < -32f || p.x > size.width + 32f || p.y < -32f || p.y > size.height + 32f) return@forEach
            val fade = (1f - ((number.age - 0.55f) / 0.45f).coerceIn(0f, 1f))
            val label = text.measure(aeroDamageLabel(number.amount), damageStyle)
            drawText(label, topLeft = p - Offset(label.size.width / 2f, label.size.height.toFloat()), alpha = fade)
        }
        frame.selected.forEach { unit ->
            val p = project(unit.center)
            val r = (unit.radius * frame.zoom * min(sx, sy)).coerceAtLeast(8.dp.toPx())
            val bounds = nativeUnitHealthBarBounds(
                p, unit.barRadius, frame.zoom, sx, sy,
                unit.barScaledDown, unit.barMini
            )
            val selectionOutside = p.x + r < 0 || p.x - r > size.width || p.y + r < 0 || p.y - r > size.height
            val barOutside = bounds.right < 0 || bounds.left > size.width || bounds.top > size.height ||
                    bounds.bottom + 20f * max(1f, frame.zoom) * sy < 0
            if (selectionOutside && barOutside) return@forEach
            val arm = (r * 0.36f).coerceIn(4.dp.toPx(), 12.dp.toPx())
            if (unit.selected || unit.hovered || unit.glowHighlight) for (x in listOf(-1f, 1f)) for (y in listOf(
                -1f,
                1f
            )) {
                val corner = p + Offset(x * r, y * r)
                val width = if (unit.glowHighlight) 2.dp.toPx() else 1.5.dp.toPx()
                drawLine(unit.color, corner, corner - Offset(x * arm, 0f), width)
                drawLine(unit.color, corner, corner - Offset(0f, y * arm), width)
            }
            val w = bounds.width
            val origin = bounds.topLeft
            val barScale = if (!unit.barScaledDown && frame.zoom < 1f) 1f else frame.zoom
            val rowScale = barScale * sy
            var barY = origin.y
            if (unit.healthVisible) {
                val fillHeight = if (unit.barMini) bounds.height else 3f * rowScale
                drawRect(Color.Black.copy(alpha = 0.65f), origin, Size(w, bounds.height))
                drawRect(Color(0xffffc66b), origin, Size(w * unit.trail, fillHeight))
                drawRect(if (unit.hp < 0.25f) Color(0xffff6565) else unit.color, origin, Size(w * unit.hp, fillHeight))
                barY += 5f * rowScale
            }
            if (unit.shieldVisible) {
                drawRect(Color.Black.copy(alpha = 0.5f), Offset(origin.x, barY), Size(w, 3f * rowScale))
                drawRect(Color(0xff64ccff), Offset(origin.x, barY), Size(w * unit.shield, 2f * rowScale))
                barY += 4f * rowScale
            }
            for ((amount, color) in listOf(
                unit.construction to Color(0xff72d8f5),
                unit.secondary to Color(0xffffcb74)
            )) {
                if (amount >= 0f) {
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(origin.x, barY), Size(w, 3f * rowScale))
                    drawRect(color, Offset(origin.x, barY), Size(w * amount.coerceIn(0f, 1f), 2f * rowScale))
                    barY += 4f * rowScale
                }
            }
        }
        frame.production.forEach { item ->
            drawAeroProductionLabel(
                item, project(item.center), item.radius * frame.zoom * min(sx, sy), text,
                frame.pausedLabel, frame.waitingLabel
            )
        }
    }
}

/** Request a frame from the platform HUD scheduler after camera or viewport changes. */
internal expect fun requestWorldPresentationFrame()
