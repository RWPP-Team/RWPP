/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.FrameBeginEvent
import io.github.rwpp.api.event.events.SimulationAfterTickEvent
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.replay.AeroReplaySeekState
import io.github.rwpp.game.replay.ReplayFiles
import io.github.rwpp.game.replay.ReplaySeekStatus
import io.github.rwpp.widget.v2.AeroButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun GameScreen.AeroReplayTimelineControls(accent: Color) {
    val replayName = AeroReplaySeekState.replayName
    val metadata = AeroReplaySeekState.metadata
    val metadataLoading = AeroReplaySeekState.metadataLoading
    val currentTick = AeroReplaySeekState.currentTick
    val startTick = AeroReplaySeekState.startTick
    val nativeAccessAvailable = AeroReplaySeekState.nativeAccessAvailable
    val seeking = AeroReplaySeekState.seeking
    val failureMessage = AeroReplaySeekState.failureMessage
    val progressTickCount = AeroReplaySeekState.progressTickCount
    val relativeCurrentTick = AeroReplaySeekState.relativeCurrentTick
    val durationMillis = AeroReplaySeekState.durationMillis
    val relativeCurrentTimeMillis = AeroReplaySeekState.relativeCurrentTimeMillis
    var previewTick by remember(replayName) { mutableIntStateOf(relativeCurrentTick) }
    var dragging by remember(replayName) { mutableStateOf(false) }

    LaunchedEffect(replayName) {
        if (replayName != null && AeroReplaySeekState.metadata == null) {
            AeroReplaySeekState.beginMetadataLoad(replayName)
            val read = withContext(Dispatchers.IO) { ReplayFiles.readTimelineMetadata(replayName) }
            AeroReplaySeekState.completeMetadataLoad(replayName, read)
        }
    }

    LaunchedEffect(currentTick, startTick, seeking) {
        if (!dragging && !seeking) previewTick = AeroReplaySeekState.relativeCurrentTick
    }

    val commandless = metadata?.commandCount == 0
    val canSeek = metadata != null && progressTickCount != null && !commandless &&
            nativeAccessAvailable && !metadataLoading && !seeking
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (metadata != null && progressTickCount != null) {
            Text(
                text = if (durationMillis != null) {
                    val timelinePosition = if (dragging) {
                        "${previewTick.coerceIn(0, progressTickCount)} / $progressTickCount"
                    } else {
                        "${formatReplayDuration(relativeCurrentTimeMillis)} / ${formatReplayDuration(durationMillis)}  ·  " +
                                "$relativeCurrentTick / $progressTickCount"
                    }
                    timelinePosition
                } else {
                    "${previewTick.coerceIn(0, progressTickCount)} / $progressTickCount"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
            AeroReplayTimelineSlider(
                value = previewTick,
                maximum = progressTickCount,
                enabled = canSeek,
                accent = accent,
                onPreview = { next ->
                    dragging = true
                    previewTick = next
                },
                onFinished = { next ->
                    dragging = false
                    previewTick = next
                    AeroReplaySeekState.seekTo(next)
                },
            )
            when {
                commandless -> Text(
                    text = readI18n("rwpp.replay.seekNoCommands"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                )

                !nativeAccessAvailable -> Text(
                    text = readI18n("rwpp.replay.seekAccessUnavailable"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                )
            }
        } else if (!metadataLoading) {
            Text(
                text = readI18n("rwpp.replay.seekUnavailable"),
                color = MaterialTheme.colorScheme.error,
                fontSize = 10.sp,
            )
        }

        if (seeking) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = readI18n("rwpp.replay.seeking"),
                    color = accent,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                AeroButton(
                    text = readI18n("rwpp.replay.cancelSeek"),
                    modifier = Modifier.weight(1f),
                    accentColor = accent,
                    onClick = AeroReplaySeekState::cancelSeek,
                )
            }
        } else if (AeroReplaySeekState.status == ReplaySeekStatus.FAILED && failureMessage != null) {
            Text(
                text = readI18n("rwpp.replay.seekReloadFailed", failureMessage),
                color = MaterialTheme.colorScheme.error,
                fontSize = 10.sp,
            )
        }
    }
}

/** Keeps tick hooks mounted while replay controls are hidden or collapsed. */
@Composable
internal fun GameScreen.AeroReplaySeekRuntime() {
    DisposableEffect(Unit) {
        AeroReplaySeekState.ensureReplayLoadListener()
        val owner = EventOwner()
        GlobalEventChannel.subscribeGlobalAlways<FrameBeginEvent>(owner) { event ->
            AeroReplaySeekState.onFrameBegin(event)
        }
        GlobalEventChannel.subscribeGlobalAlways<SimulationAfterTickEvent>(owner) { event ->
            AeroReplaySeekState.onAfterTick(event)
        }
        onDispose {
            owner.close()
            AeroReplaySeekState.cancelSeek()
        }
    }

    OnGameUpdate {
        AeroReplaySeekState.refresh(GameEngine.getInstance())
    }
}

@Composable
private fun AeroReplayTimelineSlider(
    value: Int,
    maximum: Int,
    enabled: Boolean,
    accent: Color,
    onPreview: (Int) -> Unit,
    onFinished: (Int) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var dragValue by remember { mutableIntStateOf(value) }
    val latestMaximum by rememberUpdatedState(maximum.coerceAtLeast(1))
    val latestWidth by rememberUpdatedState(widthPx)
    val latestOnPreview by rememberUpdatedState(onPreview)
    val latestOnFinished by rememberUpdatedState(onFinished)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.8f else 0.35f)
    val shownAccent = accent.copy(alpha = if (enabled) 1f else 0.4f)

    Box(
        modifier = Modifier.fillMaxWidth().height(28.dp)
            .onGloballyPositioned { widthPx = it.size.width }
            .pointerInput(enabled, maximum) {
                if (!enabled) return@pointerInput
                val inset = 10.dp.toPx()
                fun valueAt(x: Float): Int {
                    val trackWidth = (latestWidth - inset * 2f).coerceAtLeast(1f)
                    return (((x - inset) / trackWidth) * latestMaximum)
                        .roundToInt().coerceIn(0, latestMaximum)
                }

                detectTapGestures { offset ->
                    val selected = valueAt(offset.x)
                    dragValue = selected
                    latestOnPreview(selected)
                    latestOnFinished(selected)
                }
            }
            .pointerInput(enabled, maximum) {
                if (!enabled) return@pointerInput
                val inset = 10.dp.toPx()
                fun valueAt(x: Float): Int {
                    val trackWidth = (latestWidth - inset * 2f).coerceAtLeast(1f)
                    return (((x - inset) / trackWidth) * latestMaximum)
                        .roundToInt().coerceIn(0, latestMaximum)
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        dragValue = valueAt(offset.x)
                        latestOnPreview(dragValue)
                    },
                    onDragEnd = { latestOnFinished(dragValue) },
                    onDragCancel = {},
                ) { change, _ ->
                    change.consume()
                    dragValue = valueAt(change.position.x)
                    latestOnPreview(dragValue)
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val startX = 10.dp.toPx()
            val endX = (size.width - 10.dp.toPx()).coerceAtLeast(startX)
            val centerY = size.height / 2f
            val progress = value.coerceIn(0, maximum.coerceAtLeast(1)).toFloat() / maximum.coerceAtLeast(1)
            val thumbX = startX + (endX - startX) * progress
            drawLine(trackColor, Offset(startX, centerY), Offset(endX, centerY), 4.dp.toPx(), StrokeCap.Round)
            drawLine(shownAccent, Offset(startX, centerY), Offset(thumbX, centerY), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(shownAccent, 7.dp.toPx(), Offset(thumbX, centerY))
            drawCircle(Color.White, 2.5.dp.toPx(), Offset(thumbX, centerY))
        }
    }
}

private fun formatReplayDuration(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    val remainingSeconds = seconds % 60
    fun twoDigits(value: Int): String = value.toString().padStart(2, '0')
    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(remainingSeconds)}"
    } else {
        "${seconds / 60}:${twoDigits(remainingSeconds)}"
    }
}
