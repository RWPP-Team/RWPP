/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.events.GameRecoveryEvent
import io.github.rwpp.api.event.events.GameRecoveryStage
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun nativeGameFailure(failure: Throwable): Throwable {
    var cause = failure
    while (cause is InvocationTargetException) {
        val nested = cause.targetException ?: break
        if (nested === cause) break
        cause = nested
    }
    // JVM termination/resource failures cannot be made recoverable by a game-session boundary.
    if (cause is VirtualMachineError || cause is ThreadDeath) throw cause
    return cause
}

/** Receive workers only enqueue. Teardown always runs on the game owner, outside network locks. */
internal object NativeGameFaults {
    private data class Failure(val engine: GameEngine, val error: Throwable, val source: String)

    private val pending = AtomicReference<Failure?>()
    private val sequence = AtomicLong()
    @Volatile
    private var quarantinedEngine: GameEngine? = null
    fun nextId(): Long = sequence.incrementAndGet()
    fun isPending(engine: GameEngine): Boolean = pending.get()?.engine === engine

    /** Explicit host/join/new-map intent releases quarantine; dismissing the warning does not. */
    fun onNewSession() {
        val engine = GameEngine.getInstance() ?: return
        if (quarantinedEngine === engine && !isPending(engine)) {
            quarantinedEngine = null
            engine.gamePaused = false
        }
    }

    fun report(engine: GameEngine, error: Throwable, source: String) {
        val failure = Failure(engine, nativeGameFailure(error), source)
        if (!pending.compareAndSet(null, failure)) return
        logger().warn("Native $source failed; stopping the affected game session", failure.error)
        GameScope.launch { recoverPending(engine) }
    }

    fun runFrame(engine: GameEngine, operation: () -> Unit) {
        ClientTimelineGuard.poll(engine.networkEngine)
        recoverPending(engine)
        // Keep a broken native callback/world out of the idle menu loop. The host's normal
        // dispatcher pump still runs; a newly started game reactivates the engine explicitly.
        if (quarantinedEngine === engine) {
            if (!engine.gameLoopActive) return
            quarantinedEngine = null
        }
        try {
            operation()
        } catch (failure: Throwable) {
            report(engine, failure, "game frame")
            recoverPending(engine)
        }
    }

    fun runPacket(engine: GameEngine, source: String, operation: () -> Unit) {
        if (isPending(engine)) return
        try {
            operation()
        } catch (failure: Throwable) {
            report(engine, failure, source)
        }
    }

    private fun recoverPending(engine: GameEngine) {
        val failure = pending.get() ?: return
        if (failure.engine !== engine) return // An older queued cleanup must not consume a newer engine's failure.
        if (GameEngine.getInstance() !== engine) {
            pending.compareAndSet(failure, null)
            return
        }
        // The failed world is not allowed to execute another simulation step.
        engine.gamePaused = true
        engine.gameLoopActive = false
        quarantinedEngine = engine
        fun cleanup(block: () -> Unit) {
            try {
                block()
            } catch (secondary: Throwable) {
                val cause = nativeGameFailure(secondary)
                if (cause !== failure.error) failure.error.addSuppressed(cause)
                logger().warn("Native session recovery cleanup failed", cause)
            }
        }
        try {
            cleanup { engine.networkEngine.disconnect("game error") }
            cleanup { NativeGameLifecycle.onMenuShown() }
            cleanup { GamePlatform.closeGameScreen() }
            broadcastNativeEvent(
                GameRecoveryEvent(
                    nextId(),
                    GameRecoveryStage.SESSION_ENDED,
                    "${failure.source}: ${failure.error.javaClass.simpleName}: ${failure.error.message.orEmpty()}",
                    failure.error
                )
            )
        } finally {
            engine.gamePaused = true
            engine.gameLoopActive = false
            pending.compareAndSet(failure, null)
        }
    }
}
