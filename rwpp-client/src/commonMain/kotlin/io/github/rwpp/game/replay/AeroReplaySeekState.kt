/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.FrameBeginEvent
import io.github.rwpp.api.event.events.ReplayLoadEvent
import io.github.rwpp.api.event.events.ReplayLoadPhase
import io.github.rwpp.api.event.events.SimulationAfterTickEvent
import io.github.rwpp.game.nativeaccess.NativeReplayFileNameAccess
import io.github.rwpp.game.nativeaccess.NativeReplaySeekAccess
import kotlinx.coroutines.launch

internal enum class ReplaySeekStatus { IDLE, SEEKING, RELOADING, FAILED }

/**
 * Seeking is sequential native playback. Backward moves reload the same replay's initial save;
 * the simulation then advances in bounded batches until the requested tick.
 */
internal object AeroReplaySeekState {
    var replayName by mutableStateOf<String?>(null)
        private set
    var metadata by mutableStateOf<ReplayTimelineMetadata?>(null)
        private set
    var metadataLoading by mutableStateOf(false)
        private set
    var currentTick by mutableIntStateOf(0)
        private set
    var startTick by mutableIntStateOf(-1)
        private set
    var currentTimeMillis by mutableIntStateOf(0)
        private set
    var startTimeMillis by mutableIntStateOf(-1)
        private set
    var nativeAccessAvailable by mutableStateOf(false)
        private set
    var status by mutableStateOf(ReplaySeekStatus.IDLE)
        private set
    var failureMessage by mutableStateOf<String?>(null)
        private set

    private var targetTick: Int? = null
    private var frameTicks = 0
    private var frameStartedAtNanos = 0L
    private var preparedReplayName: String? = null
    private var internalReloadName: String? = null
    private var replayLoadOwner: EventOwner? = null
    private var seekRequestPending = false

    val seeking: Boolean
        get() = status == ReplaySeekStatus.SEEKING || status == ReplaySeekStatus.RELOADING

    fun prepareReplay(name: String) {
        ensureReplayLoadListener()
        if (seeking) cancelSeek()
        resetReplayState(name)
        preparedReplayName = name
    }

    private fun resetReplayState(name: String) {
        replayName = name
        metadata = null
        metadataLoading = true
        currentTick = 0
        startTick = -1
        currentTimeMillis = 0
        startTimeMillis = -1
        nativeAccessAvailable = false
        targetTick = null
        status = ReplaySeekStatus.IDLE
        failureMessage = null
    }

    fun beginMetadataLoad(name: String) {
        if (replayName != name) return
        metadataLoading = true
        metadata = null
    }

    fun completeMetadataLoad(name: String, result: ReplayTimelineMetadata?) {
        if (replayName != name) return
        metadata = result
        metadataLoading = false
    }

    fun onReplayLoad(event: ReplayLoadEvent) {
        val isInternalReload = internalReloadName == event.name
        when (event.phase) {
            ReplayLoadPhase.BEFORE -> {
                if (isInternalReload) return
                if (preparedReplayName == event.name) {
                    preparedReplayName = null
                } else {
                    if (seeking) stopSeeking(GameEngine.getInstance() as? RWGameEngine)
                    resetReplayState(event.name)
                }
            }

            ReplayLoadPhase.FAILED -> {
                if (isInternalReload) return
                if (replayName == event.name) {
                    targetTick = null
                    metadata = null
                    metadataLoading = false
                    status = ReplaySeekStatus.FAILED
                    failureMessage = event.error?.localizedMessage ?: "Replay load failed"
                }
            }

            ReplayLoadPhase.LOADED -> {
                if (replayName == event.name && GameEngine.getInstance().replayEngine.isPlayingReplay) {
                    val engine = GameEngine.getInstance()
                    startTick = engine.simulationTick
                    startTimeMillis = engine.gameTimeMillis
                }
            }
        }
    }

    fun ensureReplayLoadListener() {
        if (replayLoadOwner != null) return
        synchronized(this) {
            if (replayLoadOwner != null) return
            val owner = EventOwner()
            GlobalEventChannel.subscribeGlobalAlways<ReplayLoadEvent>(owner) { event -> onReplayLoad(event) }
            replayLoadOwner = owner
        }
    }

    fun onFrameBegin(event: FrameBeginEvent) {
        frameStartedAtNanos = System.nanoTime()
        if (status == ReplaySeekStatus.SEEKING && event.engine.replayEngine.isPlayingReplay) {
            frameTicks = 0
        }
    }

    fun onAfterTick(event: SimulationAfterTickEvent) {
        val target = targetTick ?: return
        if (status != ReplaySeekStatus.SEEKING) return
        val engine = event.engine
        val access = engine as? NativeReplaySeekAccess
        if (access == null) {
            engine.gameSpeed = 0f
            targetTick = null
            status = ReplaySeekStatus.FAILED
            failureMessage = "Native replay tick access is unavailable"
            return
        }

        if (event.tick >= target) {
            currentTick = event.tick
            currentTimeMillis = engine.gameTimeMillis
            access.rwppPendingGameStepTime(0f)
            engine.gameSpeed = 0f
            targetTick = null
            status = ReplaySeekStatus.IDLE
            failureMessage = null
            return
        }

        if (!engine.replayEngine.isPlayingReplay) {
            access.rwppPendingGameStepTime(0f)
            engine.gameSpeed = 0f
            targetTick = null
            status = ReplaySeekStatus.FAILED
            failureMessage = "Replay stopped before the requested tick"
            return
        }

        frameTicks++
        val frameElapsedNanos = System.nanoTime() - frameStartedAtNanos
        if (frameTicks >= MAX_TICKS_PER_FRAME || frameElapsedNanos >= MAX_FRAME_WORK_NANOS) {
            // Clearing the accumulator makes the native high-speed while-loop exit this frame.
            access.rwppPendingGameStepTime(0f)
        }
        // The native end block sets 0.25x; seeking must keep progressing after that marker until
        // the requested recorded tick, including commandless replays whose end marker is early.
        engine.gameSpeed = SEEK_SPEED
    }

    fun refresh(engine: GameEngine) {
        val replaying = engine.replayEngine.isPlayingReplay
        nativeAccessAvailable = engine is NativeReplaySeekAccess
        if (replaying) {
            if (replayName == null) {
                val nativeName = (engine.replayEngine as? NativeReplayFileNameAccess)
                    ?.rwppReplayFileName()
                    ?.takeIf(String::isNotBlank)
                if (nativeName != null) prepareReplay(nativeName)
            }
            currentTick = engine.simulationTick
            currentTimeMillis = engine.gameTimeMillis
            if (startTick < 0 && replayName != null) {
                startTick = engine.simulationTick
                startTimeMillis = engine.gameTimeMillis
            }
        } else if (status == ReplaySeekStatus.SEEKING) {
            stopSeeking(engine as? RWGameEngine)
        }
    }

    val progressTickCount: Int?
        get() {
            val start = startTick
            val endFrame = metadata?.stoppedFrame ?: return null
            if (start < 0 || endFrame < start || endFrame == Int.MAX_VALUE) return null
            return endFrame + 1 - start
        }

    val relativeCurrentTick: Int
        get() {
            val start = startTick
            val count = progressTickCount ?: return 0
            return (currentTick - start).coerceIn(0, count)
        }

    val relativeCurrentTimeMillis: Int
        get() {
            val start = startTimeMillis
            val end = metadata?.gameTimeMillis ?: return 0
            if (start < 0 || end < start) return 0
            return (currentTimeMillis - start).coerceIn(0, end - start)
        }

    val durationMillis: Int?
        get() {
            val start = startTimeMillis
            val end = metadata?.gameTimeMillis ?: return null
            return if (start >= 0 && end >= start) end - start else null
        }

    fun seekTo(requestedRelativeTick: Int) {
        val name = replayName ?: return
        val timeline = metadata ?: return
        val start = startTick
        val range = progressTickCount ?: return
        if (timeline.commandCount == 0 || !nativeAccessAvailable || seeking || seekRequestPending) return
        val target = start + requestedRelativeTick.coerceIn(0, range)
        seekRequestPending = true
        GameScope.launch {
            try {
                val engine = GameEngine.getInstance() as? RWGameEngine ?: return@launch
                val access = engine as? NativeReplaySeekAccess ?: run {
                    nativeAccessAvailable = false
                    status = ReplaySeekStatus.FAILED
                    failureMessage = "Native replay tick access is unavailable"
                    return@launch
                }
                if (!engine.replayEngine.isPlayingReplay) return@launch

                val now = engine.simulationTick
                access.rwppPendingGameStepTime(0f)
                failureMessage = null
                if (target < now) {
                    status = ReplaySeekStatus.RELOADING
                    targetTick = target
                    engine.gameSpeed = 0f
                    internalReloadName = name
                    val loaded = try {
                        engine.replayEngine.loadReplay(name)
                    } catch (failure: Throwable) {
                        failureMessage = failure.localizedMessage ?: failure::class.simpleName ?: "Replay load failed"
                        false
                    } finally {
                        internalReloadName = null
                    }
                    if (!loaded || !engine.replayEngine.isPlayingReplay) {
                        targetTick = null
                        status = ReplaySeekStatus.FAILED
                        if (failureMessage == null) failureMessage = "Replay load failed"
                        engine.gameSpeed = 0f
                        (engine as? NativeReplaySeekAccess)?.rwppPendingGameStepTime(0f)
                        return@launch
                    }
                    (engine as? NativeReplaySeekAccess)?.rwppPendingGameStepTime(0f)
                    currentTick = engine.simulationTick
                    startTick = engine.simulationTick
                    currentTimeMillis = engine.gameTimeMillis
                    startTimeMillis = engine.gameTimeMillis
                }

                if (engine.simulationTick >= target) {
                    access.rwppPendingGameStepTime(0f)
                    engine.gameSpeed = 0f
                    targetTick = null
                    status = ReplaySeekStatus.IDLE
                } else {
                    targetTick = target
                    frameTicks = 0
                    engine.gameSpeed = SEEK_SPEED
                    status = ReplaySeekStatus.SEEKING
                }
            } finally {
                seekRequestPending = false
            }
        }
    }

    fun cancelSeek() {
        if (!seeking && targetTick == null && !seekRequestPending) return
        GameScope.launch {
            val engine = GameEngine.getInstance() as? RWGameEngine
            stopSeeking(engine)
        }
    }

    private fun stopSeeking(engine: RWGameEngine?) {
        engine?.let { current ->
            current.gameSpeed = 0f
            (current as? NativeReplaySeekAccess)?.rwppPendingGameStepTime(0f)
            currentTick = current.simulationTick
            currentTimeMillis = current.gameTimeMillis
        }
        targetTick = null
        if (status != ReplaySeekStatus.FAILED) status = ReplaySeekStatus.IDLE
    }

    private const val MAX_TICKS_PER_FRAME = 64
    private const val MAX_FRAME_WORK_NANOS = 10_000_000L
    private const val SEEK_SPEED = 64f
}
