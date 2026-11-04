/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config

import io.github.rwpp.api.config.Config
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/** Persistent local database of played matches and per-mission campaign progress. */
@Single
@Serializable
data class MatchHistory(
    var matches: MutableList<MatchHistoryEntry> = mutableListOf(),
    var campaignMissions: MutableList<CampaignMissionProgress> = mutableListOf(),
) : Config {
    @Synchronized
    fun allMatches(): List<MatchHistoryEntry> = matches.toList()

    @Synchronized
    fun match(id: String): MatchHistoryEntry? = matches.firstOrNull { it.id == id }

    /** Bind the active history row to the exact filename reported by the native replay writer. */
    @Synchronized
    fun associateReplayFile(id: String, replayFileName: String): Boolean {
        if (!isSafeReplayFileName(replayFileName)) return false
        val index = matches.indexOfFirst { it.id == id }
        if (index < 0) return false
        val entry = matches[index]
        if (entry.replayFileName != null) return false
        // Native replay names are timestamp-based and can collide within one second. The current
        // writer is authoritative for its active row; invalidate any older row that reused the
        // same name so an overwritten file can never open as the wrong historical match.
        for (otherIndex in matches.indices) {
            if (otherIndex != index && matches[otherIndex].replayFileName == replayFileName) {
                matches[otherIndex] = matches[otherIndex].copy(replayFileName = null)
            }
        }
        matches[index] = entry.copy(replayFileName = replayFileName)
        return true
    }

    @Synchronized
    fun campaignProgress(mapPath: String): CampaignMissionProgress? {
        val key = normalizeHistoryMapPath(mapPath)
        return campaignMissions.firstOrNull { normalizeHistoryMapPath(it.mapPath) == key }
    }

    /** Detail retention uses the match date; lifetime campaign progress is intentionally separate. */
    @Synchronized
    fun pruneMatches(nowMillis: Long, retentionDays: Int, protectedMatchId: String? = null): Int {
        if (retentionDays == 0) return 0
        val days = retentionDays.takeIf { it in 1..36_500 } ?: 30
        val cutoff = nowMillis - days.toLong() * 86_400_000L
        val before = matches.size
        matches.removeAll { it.id != protectedMatchId && it.startedAtMillis < cutoff }
        return before - matches.size
    }

    @Synchronized
    fun recordMatchStarted(entry: MatchHistoryEntry) {
        if (matches.any { it.id == entry.id }) return
        matches.add(entry)
        (if (entry.matchType == MatchType.CAMPAIGN) campaignMissionKey(entry.mapPath) else null)?.let { key ->
            val current = campaignMissions.firstOrNull { normalizeHistoryMapPath(it.mapPath) == key }
            if (current == null) {
                campaignMissions.add(
                    CampaignMissionProgress(
                        mapPath = key,
                        attempts = 1,
                        lastPlayedAtMillis = entry.startedAtMillis,
                    ),
                )
            } else {
                val index = campaignMissions.indexOf(current)
                campaignMissions[index] = current.copy(
                    attempts = current.attempts + 1,
                    lastPlayedAtMillis = entry.startedAtMillis,
                    lastResult = MatchOutcome.IN_PROGRESS,
                )
            }
        }
    }

    @Synchronized
    fun recordMatchFinished(
        id: String,
        outcome: MatchOutcome,
        durationMillis: Long,
        finishedAtMillis: Long,
        playerStats: List<MatchPlayerStats> = emptyList(),
    ) {
        val index = matches.indexOfFirst { it.id == id }
        if (index < 0) return
        val entry = matches[index]
        if (entry.outcome != MatchOutcome.IN_PROGRESS) return
        matches[index] = entry.copy(
            outcome = outcome,
            durationMillis = durationMillis.coerceAtLeast(0),
            finishedAtMillis = finishedAtMillis,
            playerStats = playerStats.ifEmpty { entry.playerStats },
        )

        (if (entry.matchType == MatchType.CAMPAIGN) campaignMissionKey(entry.mapPath) else null)?.let { key ->
            val current = campaignMissions.firstOrNull { normalizeHistoryMapPath(it.mapPath) == key } ?: return@let
            val progressIndex = campaignMissions.indexOf(current)
            val won = outcome == MatchOutcome.WIN
            val lost = outcome == MatchOutcome.LOSS
            campaignMissions[progressIndex] = current.copy(
                wins = current.wins + if (won) 1 else 0,
                losses = current.losses + if (lost) 1 else 0,
                lastCompletedAtMillis = if (won) finishedAtMillis else current.lastCompletedAtMillis,
                bestTimeMillis = if (won) minOfNullable(
                    current.bestTimeMillis,
                    durationMillis
                ) else current.bestTimeMillis,
                lastResult = outcome,
            )
        }
    }

    companion object {
        /** Restore interrupted entries as abandoned before the new game session begins. */
        @Synchronized
        fun recoverInterrupted(history: MatchHistory, nowMillis: Long): Boolean {
            val interrupted = history.matches.filter { it.outcome == MatchOutcome.IN_PROGRESS }
            if (interrupted.isEmpty()) return false
            interrupted.forEach { entry ->
                history.recordMatchFinished(
                    entry.id,
                    MatchOutcome.ABANDONED,
                    entry.durationMillis,
                    nowMillis,
                )
            }
            return true
        }
    }
}

@Serializable
data class MatchHistoryEntry(
    val id: String,
    val startedAtMillis: Long,
    val mapPath: String,
    val matchType: MatchType,
    val difficulty: Int,
    val modeIds: List<String>,
    val playerNames: List<String> = emptyList(),
    val localTeamId: Int? = null,
    val localAllyTeam: Int? = null,
    val localIsObserver: Boolean = false,
    val playerStats: List<MatchPlayerStats> = emptyList(),
    val outcome: MatchOutcome = MatchOutcome.IN_PROGRESS,
    val durationMillis: Long = 0,
    val finishedAtMillis: Long? = null,
    /** Exact native ReplayEngine filename; used as a stable name reference, not a guessed map match. */
    val replayFileName: String? = null,
)

@Serializable
data class MatchPlayerStats(
    val teamId: Int,
    val allyTeam: Int,
    val name: String,
    val isAI: Boolean,
    val isObserver: Boolean,
    val credits: Double,
    val incomeRate: Int?,
    val unitsKilled: Int,
    val buildingsKilled: Int,
    val experimentalsKilled: Int,
    val unitsLost: Int,
    val buildingsLost: Int,
    val experimentalsLost: Int,
    /** Completed production/construction observed during this match; null for legacy records. */
    val unitsBuilt: Int? = null,
    val buildingsBuilt: Int? = null,
    /** Living, completed units/buildings at the time the result was captured. */
    val unitsRemaining: Int? = null,
    val buildingsRemaining: Int? = null,
)

@Serializable
data class CampaignMissionProgress(
    val mapPath: String,
    val attempts: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val lastPlayedAtMillis: Long = 0,
    val lastCompletedAtMillis: Long? = null,
    val bestTimeMillis: Long? = null,
    val lastResult: MatchOutcome? = null,
)

@Serializable
enum class MatchType { CAMPAIGN, SKIRMISH, MULTIPLAYER, SANDBOX }

@Serializable
enum class MatchOutcome { IN_PROGRESS, WIN, LOSS, OBSERVED, ABANDONED, UNKNOWN }

internal fun normalizeHistoryMapPath(path: String): String = path
    .replace('\\', '/')
    .trim()
    .trimStart('/')
    .replace(Regex("/{2,}"), "/")
    .removeSuffixIgnoreCase(".tmx")
    .removeSuffixIgnoreCase(".rwsave")
    .lowercase()

internal fun campaignMissionKey(mapPath: String): String? {
    val normalized = normalizeHistoryMapPath(mapPath)
    return normalized.takeIf {
        it.startsWith("maps/normal/") ||
                it.startsWith("maps/challenge/") ||
                it.startsWith("maps/survival/")
    }
}

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

private fun minOfNullable(current: Long?, candidate: Long): Long =
    current?.let { minOf(it, candidate) } ?: candidate

internal fun isSafeReplayFileName(name: String): Boolean =
    name.isNotBlank() && name.length <= 1024 && '/' !in name && '\\' !in name &&
            name.endsWith(".replay", ignoreCase = true)
