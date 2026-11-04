/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.MatchHistory
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.ResponsiveFilterChoices
import io.github.rwpp.widget.ResponsiveSelectionBody
import io.github.rwpp.widget.usesCompactSelectionLayout
import io.github.rwpp.widget.v2.*
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

@Single
class MissionScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val configIO = koinInject<ConfigIO>()
        val history = koinInject<MatchHistory>()
        val compact = usesCompactSelectionLayout(LocalWindowManager.current)

        var selectedIndex0 by remember { mutableIntStateOf(0) }
        var selectedIndex1 by remember { mutableIntStateOf(configIO.getGameConfig<Int>("aiDifficulty") + 2) }
        var selectedMissionIndex by remember { mutableIntStateOf(0) }
        val missionTypes = remember(game) { game.getAllMissionTypes() }
        val difficulties = listOf(
            readI18n("rwpp.difficulty.veryEasy"),
            readI18n("rwpp.difficulty.easy"),
            readI18n("rwpp.difficulty.medium"),
            readI18n("rwpp.difficulty.hard"),
            readI18n("rwpp.difficulty.veryHard"),
            readI18n("rwpp.difficulty.impossible"),
        )
        var missions by remember { mutableStateOf(listOf<Mission>()) }

        LaunchedEffect(selectedIndex0) {
            if (missionTypes.isNotEmpty() && selectedIndex0 in missionTypes.indices) {
                missions = game.getMissionsByType(missionTypes[selectedIndex0])
                selectedMissionIndex = 0
            }
        }

        AeroScaledContainer {
            AeroPanel(modifier = Modifier.fillMaxSize()) {
                ResponsiveSelectionBody(
                    compact = compact,
                    modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 0.dp),
                    compactContent = {
                        item(key = "mission-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroHeader(
                                    title = "MISSIONS",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = { navigator.pop() },
                                    clickSound = Sounds.closePopup,
                                    modifier = Modifier.width(92.dp),
                                )
                            }
                        }
                        item(key = "mission-type-filter") {
                            ResponsiveFilterChoices(
                                label = readI18n("rwpp.mission.type").uppercase(),
                                items = missionTypes.map { it.toString() },
                                selectedIndex = selectedIndex0,
                                onItemSelected = { selectedIndex0 = it.coerceIn(missionTypes.indices) },
                            )
                        }
                        item(key = "mission-difficulty-filter") {
                            ResponsiveFilterChoices(
                                label = readI18n("rwpp.common.difficulty").uppercase(),
                                items = difficulties,
                                selectedIndex = selectedIndex1.coerceIn(difficulties.indices),
                                onItemSelected = { selectedIndex1 = it },
                            )
                        }
                        if (missions.isEmpty()) {
                            item(key = "mission-empty") {
                                Text(
                                    text = readI18n("rwpp.mission.noneAvailable"),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        } else {
                            items(count = missions.size, key = { missions[it].id }) { index ->
                                val mission = missions[index]
                                val isSelected = index == selectedMissionIndex
                                AeroButton(
                                    text = mission.displayName(),
                                    onClick = { selectedMissionIndex = index },
                                    accentColor = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            item(key = "mission-selected-details") {
                                missions.getOrNull(selectedMissionIndex)?.let { currentMission ->
                                    AeroPanel(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            AeroHeader(title = currentMission.displayName())
                                            AeroDivider()
                                            AeroMapPreview(currentMission, true)
                                            MissionDetailRow(
                                                label = readI18n("rwpp.common.difficulty"),
                                                value = difficulties.getOrNull(selectedIndex1).orEmpty(),
                                            )
                                            CampaignProgressDetails(currentMission, history)
                                            AeroButton(
                                                text = readI18n("rwpp.common.start"),
                                                onClick = {
                                                    game.startNewMissionGame(selectedIndex1 - 2, currentMission)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    desktopContent = {
                        // Keep the original desktop controls and two-pane layout at Middle and Large sizes.
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroHeader(
                                    title = "MISSIONS",
                                    tag = "MISSION_SELECT",
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = { navigator.pop() },
                                    clickSound = Sounds.closePopup,
                                    modifier = Modifier.width(100.dp),
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.mission.type"),
                                    items = missionTypes.map { it.toString() },
                                    selectedIndex = selectedIndex0,
                                    onItemSelected = { selectedIndex0 = it },
                                    modifier = Modifier.weight(1f),
                                )
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.common.difficulty"),
                                    items = difficulties,
                                    selectedIndex = selectedIndex1,
                                    onItemSelected = { selectedIndex1 = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            AeroDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                AeroPanel(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize().padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(count = missions.size, key = { missions[it].id }) { index ->
                                            val mission = missions[index]
                                            val isSelected = index == selectedMissionIndex
                                            AeroButton(
                                                text = mission.displayName(),
                                                onClick = { selectedMissionIndex = index },
                                                accentColor = if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                            )
                                        }
                                    }
                                }
                                AeroPanel(modifier = Modifier.weight(0.55f).fillMaxHeight()) {
                                    val currentMission = missions.getOrNull(selectedMissionIndex)
                                    if (currentMission != null) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(16.dp),
                                            verticalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                AeroHeader(
                                                    title = currentMission.displayName(),
                                                    tag = "TACTICAL_DATA",
                                                )
                                                AeroDivider()
                                                AeroMapPreview(currentMission, true)
                                                Spacer(modifier = Modifier.height(8.dp))
                                                MissionDetailRow(
                                                    label = readI18n("rwpp.common.difficulty"),
                                                    value = difficulties.getOrNull(selectedIndex1).orEmpty(),
                                                )
                                                CampaignProgressDetails(currentMission, history)
                                            }
                                            AeroButton(
                                                text = readI18n("rwpp.common.start"),
                                                onClick = {
                                                    game.startNewMissionGame(selectedIndex1 - 2, currentMission)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    @Composable
    fun MissionDetailRow(
        label: String,
        value: String,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    @Composable
    private fun CampaignProgressDetails(mission: Mission, history: MatchHistory) {
        val progress = history.campaignProgress(mission.enginePath)
        val lastPlayed = progress?.lastPlayedAtMillis?.takeIf { it > 0 }
            ?.let(::formatHistoryTimestamp) ?: readI18n("rwpp.mission.last")
        val lastCompleted = progress?.lastCompletedAtMillis
            ?.let(::formatHistoryTimestamp) ?: readI18n("rwpp.mission.lastComplete")
        val bestTime = progress?.bestTimeMillis?.let(::formatMatchDuration)
            ?: readI18n("rwpp.common.notAvailable")

        AeroDivider()
        MissionDetailRow(readI18n("rwpp.mission.attempts"), (progress?.attempts ?: 0).toString())
        MissionDetailRow(readI18n("rwpp.mission.victories"), (progress?.wins ?: 0).toString())
        MissionDetailRow(readI18n("rwpp.mission.losses"), (progress?.losses ?: 0).toString())
        MissionDetailRow(readI18n("rwpp.mission.lastPlayed"), lastPlayed)
        MissionDetailRow(readI18n("rwpp.mission.lastCompleted"), lastCompleted)
        MissionDetailRow(readI18n("rwpp.mission.bestTime"), bestTime)
        progress?.lastResult?.let { result ->
            MissionDetailRow(readI18n("rwpp.matchHistory.result"), matchOutcomeLabel(result))
        }
    }
}
