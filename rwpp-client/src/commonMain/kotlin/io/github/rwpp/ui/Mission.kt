/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
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
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroDivider
import io.github.rwpp.widget.v2.AeroDropdownMenu
import io.github.rwpp.widget.v2.AeroHeader
import io.github.rwpp.widget.v2.AeroMapPreview
import io.github.rwpp.widget.v2.AeroPanel
import io.github.rwpp.widget.v2.AeroScaledContainer
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

@Single
class MissionScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val configIO = koinInject<ConfigIO>()

        var selectedIndex0 by remember { mutableIntStateOf(0) }
        var selectedIndex1 by remember { mutableIntStateOf(configIO.getGameConfig<Int>("aiDifficulty") + 2) }
        var selectedMissionIndex by remember { mutableIntStateOf(0) }
        val difficulties = remember {
            listOf("VeryEasy", "Easy", "Medium", "Hard", "VeryHard", "Impossible")
        }

        var missions by remember { mutableStateOf(listOf<Mission>()) }

        LaunchedEffect(selectedIndex0) {
            with(game) {
                val types = getAllMissionTypes()
                if (types.isNotEmpty() && selectedIndex0 < types.size) {
                    missions = getMissionsByType(types[selectedIndex0])
                    selectedMissionIndex = 0
                }
            }
        }

        AeroScaledContainer {
            AeroPanel(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroHeader(
                            title = readI18n("rwpp.mission.title"),
                            tag = "MISSION_SELECT",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        AeroButton(
                            text = readI18n("rwpp.common.exit"),
                            isDanger = true,
                            onClick = { navigator.pop() },
                            clickSound = Sounds.closePopup,
                            modifier = Modifier.width(100.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val missionTypes = remember(game) { game.getAllMissionTypes() }
                        AeroDropdownMenu(
                            label = readI18n("rwpp.mission.type"),
                            items = missionTypes.map { it.toString() },
                            selectedIndex = selectedIndex0,
                            onItemSelected = { selectedIndex0 = it },
                            modifier = Modifier.weight(1f)
                        )

                        AeroDropdownMenu(
                            label = readI18n("rwpp.common.difficulty"),
                            items = difficulties,
                            selectedIndex = selectedIndex1,
                            onItemSelected = { selectedIndex1 = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    AeroDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AeroPanel(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    count = missions.size,
                                    key = { missions[it].id }
                                ) { index ->
                                    val mission = missions[index]
                                    val isSelected = index == selectedMissionIndex
                                    AeroButton(
                                        text = mission.displayName(),
                                        onClick = { selectedMissionIndex = index },
                                        accentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }

                        AeroPanel(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                        ) {
                            val currentMission = missions.getOrNull(selectedMissionIndex)
                            if (currentMission != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AeroHeader(
                                            title = currentMission.displayName(),
                                            tag = "TACTICAL_DATA"
                                        )
                                        AeroDivider()

                                        AeroMapPreview(currentMission, true)

                                        Spacer(modifier = Modifier.height(8.dp))

                                        MissionDetailRow(
                                            label = readI18n("rwpp.common.difficulty"),
                                            value = difficulties.getOrNull(selectedIndex1).orEmpty()
                                        )
                                        AeroDivider()
                                        MissionDetailRow(
                                            label = /*readI18n("mission.last_played")*/ "LAST PLAYED",
                                            value = /*currentMission.lastPlayedTime*/"LAST"
                                        )
                                        AeroDivider()
                                        MissionDetailRow(
                                            label = /*readI18n("mission.last_completed")*/ "LAST COMPLETED",
                                            value = /*currentMission.lastCompletedTime*/"LAST_COMPLETE"
                                        )
                                    }

                                    AeroButton(
                                        text = readI18n("rwpp.common.start"),
                                        onClick = {
                                            val difficulty = selectedIndex1 - 2
                                            game.startNewMissionGame(difficulty, currentMission)
                                        }
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
    fun MissionDetailRow(
        label: String,
        value: String,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
