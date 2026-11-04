/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.room.*
import io.github.rwpp.game.room.AeroPlayerAction.*
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroHeaderWithCloseButton
import io.github.rwpp.widget.v2.AeroPanel
import io.github.rwpp.widget.v2.AeroToggleTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun AeroPlayerList(onDismiss: () -> Unit) {
    var roster by remember { mutableStateOf(AeroPlayerRoster()) }
    var pending by remember { mutableStateOf<Pair<Team, AeroPlayerAction>?>(null) }
    val rosterIds = remember { IdentityHashMap<Team, Int>() }
    val nextRosterId = remember { intArrayOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            roster = withContext(GameMainDispatcher) { snapshotAeroPlayers(GameEngine.getInstance()) }
            if (pending?.first?.let { player -> roster.rows.none { it.player === player } } == true || !roster.manage) pending =
                null
            delay(250.milliseconds)
        }
    }

    fun act(row: AeroPlayerRow, action: AeroPlayerAction) {
        GameScope.launch { performAeroPlayerAction(GameEngine.getInstance(), row.player, action) }
    }

    AeroPanel(Modifier.widthIn(max = 900.dp).fillMaxWidth(0.94f).fillMaxHeight(0.85f)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AeroHeaderWithCloseButton("PLAYERS", onDismissRequest = onDismiss)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groupAeroPlayers(roster.rows).forEach { (ally, players) ->
                    item("team-$ally") {
                        Text(
                            if (ally == -3) readI18n("rwpp.common.spectator") else
                                readI18n("rwpp.ingame.players.team", Team.getLetterForTeamId(ally), players.size),
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
                        )
                    }
                    items(players, key = { rosterIds.getOrPut(it.player) { nextRosterId[0]++ } }) { row ->
                        Column(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                                .padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(Modifier.size(4.dp, 25.dp).background(Color(row.color)))
                                Text(
                                    row.name,
                                    Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(row.ping, color = pingColor(row.pingMs), fontSize = 12.sp)
                            }
                            Text(
                                listOfNotNull(
                                    if (row.local) readI18n("rwpp.ingame.players.you") else null,
                                    if (row.ai) readI18n("rwpp.ingame.players.ai") else null,
                                    if (row.defeated) readI18n("rwpp.ingame.players.defeated") else null,
                                    if (row.shared) readI18n("rwpp.multiplayer.room.sharedControl") else null
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            if (!row.local && !row.ai) {
                                ChatMute.observeRevision()
                                val muted = ChatMute.isMuted(row.player)
                                AeroToggleTile(
                                    readI18n("rwpp.chat.mutePlayer"), muted,
                                    { ChatMute.setMuted(row.player, it) })
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (roster.host && !row.local) {
                                    if (row.ai && row.player is AI) {
                                        val aiPaused = remember(pending) { row.player.aiUpdatePaused }
                                        AeroButton(
                                            readI18n(
                                                if (aiPaused)
                                                    "rwpp.multiplayer.room.aiResume"
                                                else "rwpp.multiplayer.room.aiPause"
                                            ), isDanger = true,
                                            onClick = {
                                                pending = row.player to PAUSE_OR_RESUME_AI
                                            }
                                        )
                                    } else {
                                        AeroButton(
                                            readI18n("rwpp.multiplayer.room.kick"), isDanger = true,
                                            onClick = { pending = row.player to KICK })
                                    }
                                }

                                if (roster.host && row.ally != -3 && !row.defeated) {
                                    AeroButton(
                                        readI18n("menus.ingame.surrender"), isDanger = true,
                                        onClick = { pending = row.player to SURRENDER })
                                }

                                if (roster.host || row.local) AeroToggleTile(
                                    readI18n("rwpp.multiplayer.room.sharedControl"), row.shared,
                                    { act(row, SHARED_CONTROL) })
                            }
                        }
                    }
                }
            }
            pending?.let { (player, action) ->
                val name = roster.rows.firstOrNull { it.player === player }?.name.orEmpty()
                val label =
                    readI18n(
                        when (action) {
                            KICK -> "rwpp.multiplayer.room.kick"
                            SURRENDER -> "menus.ingame.surrender"
                            PAUSE_OR_RESUME_AI -> if ((player as AI).aiUpdatePaused)
                                "rwpp.multiplayer.room.aiResume"
                            else "rwpp.multiplayer.room.aiPause"

                            SHARED_CONTROL -> error("")
                        }
                    )
                Text(readI18n("rwpp.ingame.players.confirm", label, name), color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroButton(readI18n("rwpp.common.confirm"), isDanger = true, onClick = {
                        pending = null
                        GameScope.launch { performAeroPlayerAction(GameEngine.getInstance(), player, action) }
                    })
                    AeroButton(readI18n("rwpp.common.cancel"), onClick = { pending = null })
                }
            }
        }
    }
}

