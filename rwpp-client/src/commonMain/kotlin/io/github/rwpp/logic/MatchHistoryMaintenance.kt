/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.logic

import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.logging.logger
import io.github.rwpp.config.MatchHistory
import io.github.rwpp.config.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

/** Runs on the game dispatcher, alongside history writes, and writes only when expiry removes data. */
@Single(binds = [Initialization::class])
class MatchHistoryMaintenance(
    private val history: MatchHistory,
    private val settings: Settings,
    private val tracker: MatchHistoryTracker,
    private val configIO: ConfigIO,
) : Initialization {
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> get() = mutableRevisions
    private var nextCheckNanos = 0L
    private val monitor = Runnable {
        val now = System.nanoTime()
        if (now >= nextCheckNanos) {
            nextCheckNanos = now + 60_000_000_000L
            pruneExpired().onFailure { logger().error("Could not expire match history", it) }
        }
    }

    override fun init() {
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        GameMainDispatcher.addUpdateTask(monitor)
        owner.own(AutoCloseable { GameMainDispatcher.removeUpdateTask(monitor) })
    }

    /** Zero keeps all details. Applying a shorter period cleans existing expired records immediately. */
    fun updateRetention(days: Int): Result<Int> {
        if (days !in 0..36_500) return Result.failure(IllegalArgumentException("Invalid retention period"))
        val previous = settings.matchHistoryRetentionDays
        return runCatching {
            settings.matchHistoryRetentionDays = days
            try {
                configIO.saveConfig(settings)
            } catch (failure: Exception) {
                settings.matchHistoryRetentionDays = previous
                throw failure
            }
            val removed = try {
                pruneExpired().getOrThrow()
            } catch (failure: Exception) {
                settings.matchHistoryRetentionDays = previous
                runCatching { configIO.saveConfig(settings) }
                    .onFailure { logger().error("Could not restore match history retention setting", it) }
                throw failure
            }
            mutableRevisions.value++
            removed
        }
    }

    fun pruneExpired(nowMillis: Long = System.currentTimeMillis()): Result<Int> = runCatching {
        val before = history.allMatches()
        val removed = history.pruneMatches(nowMillis, settings.matchHistoryRetentionDays, tracker.currentMatch?.id)
        if (removed > 0) {
            try {
                configIO.saveConfig(history)
            } catch (failure: Exception) {
                // Retain the in-memory history if its updated file could not be persisted.
                synchronized(history) { history.matches = before.toMutableList() }
                throw failure
            }
            mutableRevisions.value++
        }
        removed
    }
}
