/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.MapNames
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.*
import io.github.rwpp.game.replay.AeroReplaySeekState
import io.github.rwpp.logic.MatchHistoryMaintenance
import io.github.rwpp.logic.MatchHistoryPerformanceDisclosure
import io.github.rwpp.logic.matchHistoryUnitKillDeathRatio
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import io.github.rwpp.widget.v2.scrollbar.ScrollbarSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import java.text.DateFormat
import java.text.NumberFormat
import java.util.*

@Single
class MatchHistoryScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val history = koinInject<MatchHistory>()
        val game = koinInject<Game>()
        val configIO = koinInject<ConfigIO>()
        val settings = koinInject<Settings>()
        val maintenance = koinInject<MatchHistoryMaintenance>()
        val revision by maintenance.revisions.collectAsState()
        var retentionDays by remember { mutableIntStateOf(settings.matchHistoryRetentionDays) }
        var retentionError by remember { mutableStateOf<String?>(null) }
        var replayCandidates by remember { mutableStateOf<List<MatchHistoryReplayCandidate>>(emptyList()) }
        var replayFileNamesByMatch by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var replayCatalogReady by remember { mutableStateOf(false) }
        var unavailableReplayMatchIds by remember { mutableStateOf(emptySet<String>()) }
        val replayCandidatesByName = remember(replayCandidates) {
            replayCandidates.associateBy(MatchHistoryReplayCandidate::fileName)
        }
        val entries = remember(history, revision, retentionDays) {
            history.allMatches().sortedByDescending { it.startedAtMillis }
        }
        LaunchedEffect(game) {
            replayCatalogReady = false
            replayCandidates = emptyList()
            replayFileNamesByMatch = emptyMap()
            val candidatesByName = linkedMapOf<String, MatchHistoryReplayCandidate>()
            try {
                game.getReplayPages(refresh = true).collect { page ->
                    page.items.forEach { replay ->
                        candidatesByName[replay.name] = MatchHistoryReplayCandidate(
                            fileName = replay.name,
                            mapName = replayAssociationMapName(replay.name),
                            timestampMillis = replay.timestampMillis,
                        )
                    }
                }
                val candidates = candidatesByName.values.toList()
                val currentEntries = history.allMatches()
                val resolved = withContext(Dispatchers.Default) {
                    val legacy = resolveLegacyMatchReplayLinks(currentEntries, candidates)
                    val exact = resolveExactMatchReplayLinks(
                        currentEntries,
                        candidates.mapTo(hashSetOf()) { it.fileName },
                    )
                    Triple(exact + legacy, legacy, candidates)
                }
                replayCandidates = resolved.third
                replayFileNamesByMatch = resolved.first
                replayCatalogReady = true
                var associationChanged = false
                for ((matchId, replayName) in resolved.second) {
                    associationChanged = history.associateReplayFile(matchId, replayName) || associationChanged
                }
                if (associationChanged) {
                    try {
                        withContext(GameMainDispatcher) { configIO.saveConfig(history) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The current in-memory link remains usable; a later config save can persist it.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep unresolved records unlinked if catalog enumeration or metadata parsing fails.
                replayCatalogReady = false
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(maintenance) {
            withContext(GameMainDispatcher) {
                maintenance.pruneExpired().onFailure { failure ->
                    retentionError = failure.message.orEmpty()
                }
            }
        }

        AeroScaledContainer {
            AeroPanel(Modifier.fillMaxSize()) {
                LazyColumnScrollbar(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    settings = ScrollbarSettings.Aero.copy(
                        alwaysShowScrollbar = listState.canScrollForward || listState.canScrollBackward,
                    ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "history-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroHeader(title = "MATCH HISTORY", modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(12.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = { navigator.pop() },
                                    modifier = Modifier.width(92.dp),
                                )
                            }
                        }
                        item(key = "history-summary") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroBadge("MATCHES ${entries.size}")
                                AeroBadge(
                                    "WINS ${entries.count { it.outcome == MatchOutcome.WIN }}",
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }

                        item(key = "history-retention") {
                            MatchHistoryRetentionControl(
                                selectedDays = retentionDays,
                                error = retentionError,
                                onSelected = { days ->
                                    val previous = retentionDays
                                    retentionDays = days
                                    retentionError = null
                                    GameScope.launch {
                                        maintenance.updateRetention(days).onFailure { failure ->
                                            retentionDays = previous
                                            retentionError = failure.message.orEmpty()
                                        }
                                    }
                                },
                            )
                        }

                        if (entries.isEmpty()) {
                            item(key = "history-empty") {
                                AeroPanel(Modifier.fillMaxWidth()) {
                                    Text(
                                        readI18n("rwpp.matchHistory.empty"),
                                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        } else {
                            items(entries, key = MatchHistoryEntry::id) { entry ->
                                val replayFileName = replayFileNamesByMatch[entry.id]
                                    ?: entry.replayFileName?.takeIf {
                                        !replayCatalogReady && entry.outcome != MatchOutcome.IN_PROGRESS
                                    }
                                MatchHistoryCard(
                                    entry = entry,
                                    replayFileName = replayFileName,
                                    replayUnavailable = entry.outcome != MatchOutcome.IN_PROGRESS &&
                                            replayCatalogReady &&
                                            entry.replayFileName != null && replayFileName == null,
                                    replayError = entry.id in unavailableReplayMatchIds,
                                    onWatchReplay = replayFileName?.let { name ->
                                        {
                                            val expected = replayCandidatesByName[name]
                                            GameScope.launch {
                                                val currentReplay = withContext(GameMainDispatcher) {
                                                    game.getReplay(name)
                                                }
                                                val verifiedReplay = currentReplay?.takeIf {
                                                    matchHistoryReplayIdentityMatches(
                                                        expectedFileName = name,
                                                        currentFileName = it.name,
                                                        expectedTimestampMillis = expected?.timestampMillis,
                                                        currentTimestampMillis = it.timestampMillis,
                                                    )
                                                }
                                                if (verifiedReplay == null) {
                                                    unavailableReplayMatchIds = unavailableReplayMatchIds + entry.id
                                                } else {
                                                    unavailableReplayMatchIds = unavailableReplayMatchIds - entry.id
                                                    withContext(GameMainDispatcher) {
                                                        AeroReplaySeekState.prepareReplay(name)
                                                        game.watchReplay(verifiedReplay)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchHistoryRetentionControl(
    selectedDays: Int,
    error: String?,
    onSelected: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            readI18n("rwpp.matchHistory.retention"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        val optionIndex = matchHistoryRetentionOptions.indexOf(selectedDays)
            .takeIf { it >= 0 } ?: matchHistoryRetentionOptions.indexOf(30)
        AeroDropdownMenu(
            items = matchHistoryRetentionOptions.map { days ->
                readI18n(matchHistoryRetentionOptionKey(days))
            },
            selectedIndex = optionIndex,
            onItemSelected = { index -> matchHistoryRetentionOptions.getOrNull(index)?.let(onSelected) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            readI18n("rwpp.matchHistory.retention.help"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (!error.isNullOrBlank()) {
            Text(
                readI18n("rwpp.matchHistory.retention.failed", error),
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MatchHistoryCard(
    entry: MatchHistoryEntry,
    replayFileName: String?,
    replayUnavailable: Boolean,
    replayError: Boolean,
    onWatchReplay: (() -> Unit)?,
) {
    AeroPanel(Modifier.fillMaxWidth()) {
        var performanceDisclosure by remember(entry.id) {
            mutableStateOf(MatchHistoryPerformanceDisclosure())
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = MapNames.getPrettyMapName(entry.mapPath),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                AeroBadge(
                    text = matchOutcomeLabel(entry.outcome),
                    color = when (entry.outcome) {
                        MatchOutcome.WIN -> MaterialTheme.colorScheme.secondary
                        MatchOutcome.LOSS -> MaterialTheme.colorScheme.error
                        MatchOutcome.OBSERVED -> MaterialTheme.colorScheme.tertiary
                        MatchOutcome.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }
            AeroDivider()
            MatchHistoryRow(readI18n("rwpp.matchHistory.type"), matchTypeLabel(entry.matchType))
            MatchHistoryRow(
                readI18n("rwpp.matchHistory.date"),
                formatHistoryTimestamp(entry.startedAtMillis),
            )
            MatchHistoryRow(
                readI18n("rwpp.matchHistory.duration"),
                formatMatchDuration(entry.durationMillis),
            )
            if (entry.matchType == MatchType.CAMPAIGN || entry.matchType == MatchType.SKIRMISH) {
                MatchHistoryRow(
                    readI18n("rwpp.common.difficulty"),
                    matchDifficultyLabel(entry.difficulty),
                )
            }
            if (entry.playerNames.isNotEmpty()) {
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.players"),
                    entry.playerNames.joinToString(),
                )
            }
            if (entry.modeIds.isNotEmpty()) {
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.rules"),
                    entry.modeIds.joinToString(),
                )
            }
            if (replayFileName != null && onWatchReplay != null) {
                AeroButton(
                    text = readI18n("rwpp.matchHistory.replay.watch"),
                    onClick = onWatchReplay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (replayUnavailable || replayError) {
                Text(
                    readI18n("rwpp.matchHistory.replay.unavailable"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (entry.playerStats.isNotEmpty()) {
                AeroDivider()
                AeroHeader(title = "PLAYER PERFORMANCE")
                entry.playerStats.forEach { stats ->
                    MatchPlayerStatistics(
                        stats = stats,
                        expanded = performanceDisclosure.isExpanded(stats.teamId),
                        onToggle = {
                            performanceDisclosure = performanceDisclosure.toggle(stats.teamId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchPlayerStatistics(
    stats: MatchPlayerStats,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = buildString {
                        append(stats.name.ifBlank { readI18n("rwpp.matchHistory.unnamedPlayer", stats.teamId) })
                        if (stats.isAI) append(" · ").append(readI18n("rwpp.matchHistory.ai"))
                        if (stats.isObserver) append(" · ").append(readI18n("rwpp.matchHistory.observer"))
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.unitKd"),
                    formatMatchHistoryUnitKd(stats),
                )
            }
            AeroButton(
                text = readI18n(
                    if (expanded) "rwpp.matchHistory.performance.hide"
                    else "rwpp.matchHistory.performance.show",
                ),
                onClick = onToggle,
                modifier = Modifier.width(128.dp),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.kills"),
                    readI18n(
                        "rwpp.matchHistory.kills.value",
                        stats.unitsKilled,
                        stats.buildingsKilled,
                        stats.experimentalsKilled,
                    ),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.losses"),
                    readI18n(
                        "rwpp.matchHistory.losses.value",
                        stats.unitsLost,
                        stats.buildingsLost,
                        stats.experimentalsLost,
                    ),
                )
                val kdCounts = matchHistoryUnitKdCounts(stats)
                Text(
                    readI18n("rwpp.matchHistory.unitKd.help", kdCounts.kills, kdCounts.losses),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.unitsBuilt"),
                    formatMatchHistoryCount(stats.unitsBuilt, readI18n("rwpp.matchHistory.unavailable")),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.buildingsBuilt"),
                    formatMatchHistoryCount(stats.buildingsBuilt, readI18n("rwpp.matchHistory.unavailable")),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.unitsRemaining"),
                    formatMatchHistoryCount(stats.unitsRemaining, readI18n("rwpp.matchHistory.unavailable")),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.buildingsRemaining"),
                    formatMatchHistoryCount(stats.buildingsRemaining, readI18n("rwpp.matchHistory.unavailable")),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.credits"),
                    java.text.NumberFormat.getIntegerInstance().format(stats.credits.toLong()),
                )
                MatchHistoryRow(
                    readI18n("rwpp.matchHistory.income"),
                    stats.incomeRate?.let { readI18n("rwpp.matchHistory.income.value", it) }
                        ?: readI18n("rwpp.common.notAvailable"),
                )
            }
        }
    }
}

@Composable
private fun MatchHistoryRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun matchOutcomeLabel(outcome: MatchOutcome): String = readI18n(
    when (outcome) {
        MatchOutcome.IN_PROGRESS -> "rwpp.matchHistory.outcome.inProgress"
        MatchOutcome.WIN -> "rwpp.matchHistory.outcome.win"
        MatchOutcome.LOSS -> "rwpp.matchHistory.outcome.loss"
        MatchOutcome.OBSERVED -> "rwpp.matchHistory.outcome.observed"
        MatchOutcome.ABANDONED -> "rwpp.matchHistory.outcome.abandoned"
        MatchOutcome.UNKNOWN -> "rwpp.matchHistory.outcome.unknown"
    },
)

@Composable
private fun matchTypeLabel(type: MatchType): String = readI18n(
    when (type) {
        MatchType.CAMPAIGN -> "rwpp.matchHistory.type.campaign"
        MatchType.SKIRMISH -> "rwpp.matchHistory.type.skirmish"
        MatchType.MULTIPLAYER -> "rwpp.matchHistory.type.multiplayer"
        MatchType.SANDBOX -> "rwpp.matchHistory.type.sandbox"
    },
)

@Composable
private fun matchDifficultyLabel(difficulty: Int): String = readI18n(
    when (difficulty.coerceIn(-2, 3)) {
        -2 -> "rwpp.difficulty.veryEasy"
        -1 -> "rwpp.difficulty.easy"
        0 -> "rwpp.difficulty.medium"
        1 -> "rwpp.difficulty.hard"
        2 -> "rwpp.difficulty.veryHard"
        else -> "rwpp.difficulty.impossible"
    },
)

internal fun formatHistoryTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

internal fun formatMatchDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3_600
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}

internal data class MatchHistoryUnitKdCounts(val kills: Int, val losses: Int)

internal fun matchHistoryUnitKdCounts(stats: MatchPlayerStats): MatchHistoryUnitKdCounts =
    MatchHistoryUnitKdCounts(
        kills = (stats.unitsKilled.coerceAtLeast(0) + stats.experimentalsKilled.coerceAtLeast(0)),
        losses = (stats.unitsLost.coerceAtLeast(0) + stats.experimentalsLost.coerceAtLeast(0)),
    )

internal fun formatMatchHistoryUnitKd(stats: MatchPlayerStats): String {
    val ratio = matchHistoryUnitKillDeathRatio(
        stats.unitsKilled,
        stats.experimentalsKilled,
        stats.unitsLost,
        stats.experimentalsLost,
    )
    if (ratio == null) return if (matchHistoryUnitKdCounts(stats).kills > 0) "∞" else "—"
    return NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(ratio)
}

internal fun formatMatchHistoryCount(value: Int?, unavailable: String): String =
    value?.coerceAtLeast(0)?.toString() ?: unavailable

internal val matchHistoryRetentionOptions = listOf(7, 30, 90, 365, 0)

internal fun matchHistoryRetentionOptionKey(days: Int): String = when (days) {
    7 -> "rwpp.matchHistory.retention.7days"
    30 -> "rwpp.matchHistory.retention.30days"
    90 -> "rwpp.matchHistory.retention.90days"
    365 -> "rwpp.matchHistory.retention.365days"
    else -> "rwpp.matchHistory.retention.unlimited"
}
