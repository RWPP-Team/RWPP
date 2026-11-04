/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.config.Settings
import io.github.rwpp.game.minimap.*
import io.github.rwpp.graphics.hudImageFromPixels
import io.github.rwpp.widget.v2.AeroPanel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Letterbox projection shared by drawing and hit testing, including non-square maps. */
internal data class MinimapProjection(
    val worldWidth: Float,
    val worldHeight: Float,
    val width: Float,
    val height: Float
) {
    val scale = minOf(width / worldWidth.coerceAtLeast(1f), height / worldHeight.coerceAtLeast(1f))
    val left = (width - worldWidth * scale) / 2f
    val top = (height - worldHeight * scale) / 2f
    fun worldToMap(x: Float, y: Float) = Offset(left + x * scale, top + y * scale)
    fun mapToWorld(position: Offset): Offset? {
        if (scale <= 0 || position.x < left || position.y < top ||
            position.x > width - left || position.y > height - top
        ) return null
        return Offset(
            ((position.x - left) / scale).coerceIn(0f, worldWidth),
            ((position.y - top) / scale).coerceIn(0f, worldHeight)
        )
    }
}

private data class MinimapFrame(
    val terrain: ImageBitmap,
    val terrainPixels: IntArray,
    val worldWidth: Float,
    val worldHeight: Float,
    val dots: List<MinimapDot>,
    val native: MinimapNativeSnapshot,
)

private data class MinimapCamera(val x: Float, val y: Float, val width: Float, val height: Float)

@Composable
internal fun GameScreen.AeroMinimap() {
    val sampler = remember { NativeMinimapSampler() }
    val settings = koinInject<Settings>()
    var frame by remember { mutableStateOf<MinimapFrame?>(null) }
    val schedule = remember { MinimapRefreshSchedule() }
    val camera = remember { mutableStateOf(MinimapCamera(0f, 0f, 0f, 0f)) }
    var cameraTarget by remember { mutableStateOf<Offset?>(null) }
    var clickSignals by remember { mutableStateOf<List<MinimapSignal>>(emptyList()) }
    var clickTime by remember { mutableLongStateOf(0L) }
    OnGameUpdate {
        val engine = GameEngine.getInstance()
        cameraTarget?.let {
            engine.centerCameraOn(it.x, it.y)
            engine.clampCamera()
            cameraTarget = null
        }
        val now = System.currentTimeMillis()
        clickSignals = AeroMinimapOrders.snapshot(now)
        if (clickSignals.isNotEmpty()) clickTime = now
        // Camera changes invalidate drawing only, at the native frame rate.
        camera.value =
            MinimapCamera(engine.cameraWorldX, engine.cameraWorldY, engine.gameAreaWorldWidth, engine.viewHeight)
        if (schedule.unitsDue(now)) {
            val sampled = sampler.sample(engine, schedule.fogDue(now), settings.minimapUsePlayerColors)
            frame = sampled?.let { data ->
                // Pixel arrays are immutable after publication. Keep the previous ImageBitmap
                // while the sampler returns the same array; changed fog/map output gets a fresh
                // array which is copied into an owned platform image here.
                val previous = frame
                val terrain = previous?.takeIf { it.terrainPixels === data.terrainPixels }?.terrain
                    ?: hudImageFromPixels(data.terrainPixels, data.terrainWidth, data.terrainHeight)
                MinimapFrame(
                    terrain = terrain,
                    terrainPixels = data.terrainPixels,
                    worldWidth = data.worldWidth,
                    worldHeight = data.worldHeight,
                    dots = data.dots,
                    native = data.native,
                )
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    AeroPanel(Modifier.fillMaxSize()) {
        val snapshot = frame
        Canvas(
            Modifier.fillMaxSize().padding(5.dp).background(Color.Black)
                .pointerInput(snapshot?.worldWidth, snapshot?.worldHeight, isEditMode) {
                    if (!isEditMode) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitPointerEventScope {
                            var down: Offset? = null
                            var dragged = false
                            var secondary = false
                            var touch = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        down = change.position
                                        dragged = false
                                        secondary = isHudSecondaryPress(event)
                                        touch = change.type == PointerType.Touch
                                        change.consume()
                                    }

                                    PointerEventType.Move -> {
                                        val start = down
                                        if (start != null) {
                                            if ((change.position - start).getDistance() > touchSlop) dragged = true
                                            if (!secondary && dragged) {
                                                snapshot?.let {
                                                    cameraTarget = MinimapProjection(
                                                        it.worldWidth, it.worldHeight,
                                                        size.width.toFloat(), size.height.toFloat()
                                                    ).mapToWorld(change.position)
                                                }
                                            }
                                            change.consume()
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        val world = snapshot?.let {
                                            MinimapProjection(
                                                it.worldWidth, it.worldHeight,
                                                size.width.toFloat(), size.height.toFloat()
                                            ).mapToWorld(change.position)
                                        }
                                        if (!dragged) world?.let { target ->
                                            val touchTap = touch
                                            val secondaryTap = secondary
                                            GameScope.launch {
                                                if (!issueMinimapTap(
                                                        target.x,
                                                        target.y,
                                                        touchTap,
                                                        secondaryTap
                                                    )
                                                ) cameraTarget =
                                                    target
                                            }
                                        }
                                        change.consume()
                                        down = null
                                        secondary = false
                                    }

                                    PointerEventType.Scroll -> Unit
                                    PointerEventType.Enter, PointerEventType.Exit -> Unit
                                }
                            }
                        }
                    }
                }) {
            if (snapshot != null) {
                val projection = MinimapProjection(snapshot.worldWidth, snapshot.worldHeight, size.width, size.height)
                val extent = Size(snapshot.worldWidth * projection.scale, snapshot.worldHeight * projection.scale)
                val origin = Offset(projection.left, projection.top)
                drawImage(
                    snapshot.terrain, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize(
                        extent.width.roundToInt().coerceAtLeast(1),
                        extent.height.roundToInt().coerceAtLeast(1)
                    )
                )
                clipRect(origin.x, origin.y, origin.x + extent.width, origin.y + extent.height) {
                    snapshot.dots.forEach { dot ->
                        val position = projection.worldToMap(dot.x, dot.y)
                        val radius = dot.radiusDp.dp.toPx()
                        drawCircle(Color(dot.color), radius, position)
                        if (dot.selected) {
                            drawCircle(Color.White, radius + 0.6.dp.toPx(), position, style = Stroke(0.75.dp.toPx()))
                        }
                    }
                    snapshot.native.projectileMarkers.forEach { marker ->
                        drawCircle(
                            Color(minimapNukeMarkerColor(marker.color, snapshot.native.notificationPulseAngle)),
                            marker.radiusFraction * minOf(extent.width, extent.height),
                            projection.worldToMap(marker.x, marker.y)
                        )
                    }
                    clickSignals.forEach { signal ->
                        val age = ((clickTime - signal.time) / 700f).coerceIn(0f, 1f)
                        val p = projection.worldToMap(signal.x, signal.y)
                        drawAeroOrderPulse(p, age, accent)
                    }
                    val view = camera.value
                    drawRect(
                        accent, projection.worldToMap(view.x, view.y),
                        Size(view.width * projection.scale, view.height * projection.scale), style = Stroke(1.dp.toPx())
                    )
                    // Preserve native X/plus alerts; unit damage adds the requested ring on that same pulse/fade.
                    snapshot.native.alerts.forEach { alert ->
                        val position = projection.worldToMap(alert.x, alert.y)
                        val scale = minimapAlertDrawScale(alert.type, alert.scaleX)
                        val halfWidth = extent.width * scale
                        val halfHeight = extent.height * scale
                        val color = Color(minimapAlertColor(alert.type, snapshot.native.notificationPulseAngle))
                        val stroke = 1.dp.toPx()
                        if (alert.type == MinimapHandlerNotificationType.unit) {
                            drawCircle(
                                color, minOf(extent.width, extent.height) * scale, position,
                                style = Stroke(1.5.dp.toPx())
                            )
                            drawLine(
                                color, Offset(position.x - halfWidth, position.y - halfHeight),
                                Offset(position.x + halfWidth, position.y + halfHeight), stroke
                            )
                            drawLine(
                                color, Offset(position.x + halfWidth, position.y - halfHeight),
                                Offset(position.x - halfWidth, position.y + halfHeight), stroke
                            )
                        } else {
                            drawLine(
                                color, Offset(position.x - halfWidth, position.y),
                                Offset(position.x + halfWidth, position.y), stroke
                            )
                            drawLine(
                                color, Offset(position.x, position.y - halfHeight),
                                Offset(position.x, position.y + halfHeight), stroke
                            )
                        }
                    }
                }
                val flash = snapshot.native.borderFlashColor
                drawRect(
                    flash?.let { Color(it) } ?: accent.copy(alpha = 0.5f), origin, extent,
                    style = Stroke(if (flash == null) 1.dp.toPx() else 2.dp.toPx())
                )
            }
        }
    }
}
