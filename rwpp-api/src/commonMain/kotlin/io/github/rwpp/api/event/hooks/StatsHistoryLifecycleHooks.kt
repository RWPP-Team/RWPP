/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.StatsHandlerTeamStats
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import io.github.rwpp.api.mixin.game.stats.NativeStatsHistoryAccess
import io.github.rwpp.api.net.impl.unwrapNativeInvocationFailure
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes history snapshots against archive stats reads without taking an engine/update lock.
 * A pending read prevents new snapshots and waits for any in-flight snapshot to finish.
 */
internal class StatsHistoryLoadGate {
    private val state = AtomicInteger(0)

    fun tryEnterUpdate(): Boolean {
        while (true) {
            val current = state.get()
            if (current < 0) return false
            if (state.compareAndSet(current, current + 1)) return true
        }
    }

    fun leaveUpdate() {
        state.decrementAndGet()
    }

    fun tryEnterRead(): Boolean = state.compareAndSet(0, READ_PENDING)

    fun enterRead() {
        while (true) {
            val current = state.get()
            if (current < 0) {
                Thread.yield()
                continue
            }
            if (state.compareAndSet(current, current or READ_PENDING)) break
        }

        while (state.get() != READ_PENDING) Thread.yield()
    }

    fun leaveRead() {
        check(state.compareAndSet(READ_PENDING, 0)) { "Stats history read gate was not held" }
    }

    private companion object {
        const val READ_PENDING = Int.MIN_VALUE
    }
}

internal fun <T : Any> fillMissingStatsSlots(entries: Array<T?>, createEntry: () -> T): Int {
    var initialized = 0
    for (index in entries.indices) {
        if (entries[index] == null) {
            entries[index] = createEntry()
            initialized++
        }
    }
    return initialized
}

internal object StatsHistoryLoadCallbacks {
    private val loadGate = StatsHistoryLoadGate()


    fun guardHistorySnapshot(operation: () -> Unit) {
        if (!loadGate.tryEnterUpdate()) return
        try {
            try {
                operation()
            } catch (failure: Throwable) {
                throw unwrapNativeInvocationFailure(failure)
            }
        } finally {
            loadGate.leaveUpdate()
        }
    }

    fun repairStatsAfterRead(
        inputStream: InputNetStream,
        keepTeams: Boolean,
        operation: (InputNetStream, Boolean) -> Unit,
    ) {
        loadGate.enterRead()
        var nativeFailure: Throwable? = null
        try {
            try {
                operation(inputStream, keepTeams)
            } catch (failure: Throwable) {
                val unwrapped = unwrapNativeInvocationFailure(failure)
                nativeFailure = unwrapped
                throw unwrapped
            }
        } finally {
            try {
                initializeMissingTeamStats(GameEngine.getInstance().statsHandler)
            } catch (repairFailure: Throwable) {
                val originalFailure = nativeFailure
                if (originalFailure == null) {
                    throw repairFailure
                } else if (originalFailure !== repairFailure) {
                    originalFailure.addSuppressed(repairFailure)
                }
            } finally {
                loadGate.leaveRead()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun initializeMissingTeamStats(handler: Any) {
        val entries = (handler as NativeStatsHistoryAccess).rwppTeamStats()
        fillMissingStatsSlots(entries) { StatsHandlerTeamStats() }
    }
}

