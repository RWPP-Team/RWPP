/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardColumn
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardGroupMode
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hud.AeroStatisticsRow
import io.github.rwpp.game.hud.aeroStatisticsSnapshot
import io.github.rwpp.game.hud.isAeroLiveObserver
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroDropdownMenu
import io.github.rwpp.widget.v2.AeroHeader
import io.github.rwpp.widget.v2.AeroPanel

/** Statistics are a display-only snapshot: never replace the map's native leaderboard configuration. */
@Composable
internal fun GameScreen.AeroObserverStatistics(visible: Boolean, modifier: Modifier = Modifier) {
    var observing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    var column by remember { mutableStateOf(LeaderboardColumn.credits) }
    var grouping by remember { mutableStateOf(LeaderboardGroupMode.player) }
    var rows by remember { mutableStateOf(emptyList<AeroStatisticsRow>()) }
    var sampledMap by remember { mutableStateOf<Any?>(null) }
    var sampledColumn by remember { mutableStateOf<LeaderboardColumn?>(null) }
    var sampledGrouping by remember { mutableStateOf<LeaderboardGroupMode?>(null) }
    var lastSample by remember { mutableLongStateOf(0L) }
    OnGameUpdate {
        val engine = GameEngine.getInstance()
        val active = engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)
        observing = active
        if (!active) {
            rows = emptyList()
            sampledMap = null
        } else {
            val now = System.nanoTime()
            if (sampledMap !== engine.map || sampledColumn != column || sampledGrouping != grouping || now - lastSample >= 250_000_000L) {
                rows = aeroStatisticsSnapshot(column, grouping)
                sampledMap = engine.map
                sampledColumn = column
                sampledGrouping = grouping
                lastSample = now
            }
        }
    }
    if (!visible || (!observing && !isEditMode)) return
    val columns = remember { LeaderboardColumn.entries.filter { it != LeaderboardColumn.none } }
    val groups = remember { LeaderboardGroupMode.entries }
    AeroPanel(modifier.width(268.dp)) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AeroHeader("STATISTICS")
            AeroButton(
                text = readI18n("rwpp.observer.statistics"), subText = if (expanded) "▲" else "▼",
                onClick = { expanded = !expanded }
            )
            if (expanded) {
                AeroDropdownMenu(
                    label = readI18n("rwpp.observer.statistic"),
                    items = columns.map { readI18n("gui.leaderboard.type.${it.name}") },
                    selectedIndex = columns.indexOf(column), modifier = Modifier.fillMaxWidth(),
                    onItemSelected = { column = columns[it] }
                )
                AeroDropdownMenu(
                    label = readI18n("rwpp.observer.grouping"),
                    items = groups.map { readI18n("rwpp.observer.group.${it.name}") },
                    selectedIndex = groups.indexOf(grouping), modifier = Modifier.fillMaxWidth(),
                    onItemSelected = { grouping = groups[it] }
                )
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    rows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (row.group) 0.16f else 0.04f))
                                .padding(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(3.dp, 16.dp).background(Color(row.color)))
                            Text(
                                row.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp, fontWeight = if (row.group) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                row.value,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
