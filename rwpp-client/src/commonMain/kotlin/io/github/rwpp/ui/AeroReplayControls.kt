/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hud.isAeroLiveObserver
import io.github.rwpp.game.replay.*
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroDropdownMenu
import kotlinx.coroutines.launch

/** Compact replay HUD; the caller can hide the controls while another HUD surface is open. */
@Composable
internal fun GameScreen.AeroReplayControls(visible: Boolean = true) {
    var replaying by remember { mutableStateOf(false) }
    var playbackControls by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    var teamOptions by remember { mutableStateOf(emptyList<AeroReplayPerspectiveOption>()) }
    var selectedTeamId by remember { mutableIntStateOf(Team.grayTeam.teamId) }
    var paused by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    var hasTeamOptionsRefresh by remember { mutableStateOf(false) }
    var lastTeamOptionsRefreshNanos by remember { mutableLongStateOf(0L) }
    val session = remember { AeroReplayPerspectiveSession() }

    OnGameUpdate {
        val engine = GameEngine.getInstance()
        val isReplay = engine.replayEngine.isPlayingReplay
        val canObserve = isReplay || isAeroLiveObserver(engine)
        if (canObserve && (!session.active || !session.matches(engine))) {
            session.finish(engine)
            session.begin(engine)
            AeroReplayControlsState.showAllUnitOrders = false
        } else if (!canObserve && session.active) {
            val restore = session.finish(engine)
            if (restore != null) GameScope.launch {
                val current = GameEngine.getInstance()
                if (current === restore.engine && current.map === restore.map && !current.replayEngine.isPlayingReplay) {
                    restoreAeroReplayPerspective(restore)
                }
            }
            AeroReplayControlsState.showAllUnitOrders = false
            expanded = false
        }
        replaying = canObserve
        playbackControls = isReplay
        paused = engine.gameSpeed == 0f
        playbackSpeed = engine.gameSpeed
        if (canObserve) {
            val now = System.nanoTime()
            if (!hasTeamOptionsRefresh || now - lastTeamOptionsRefreshNanos >= TEAM_OPTIONS_REFRESH_NANOS) {
                hasTeamOptionsRefresh = true
                lastTeamOptionsRefreshNanos = now
                val refreshed = aeroReplayPerspectiveOptions()
                if (refreshed != teamOptions) teamOptions = refreshed
            }
            val currentTeamId = aeroPresentationTeam(engine)?.teamId ?: Team.grayTeam.teamId
            if (selectedTeamId != currentTeamId) selectedTeamId = currentTeamId
        } else {
            hasTeamOptionsRefresh = false
        }
    }

    if (playbackControls) AeroReplaySeekRuntime()

    DisposableEffect(session) {
        onDispose {
            val restore = session.finish(GameEngine.getInstance())
            if (restore != null) GameScope.launch {
                val current = GameEngine.getInstance()
                if (current === restore.engine && current.map === restore.map) {
                    restoreAeroReplayPerspective(restore)
                }
            }
            AeroReplayControlsState.showAllUnitOrders = false
        }
    }

    if (!visible || !replaying) return
    val accent = MaterialTheme.colorScheme.primary
    val shape = remember { RoundedCornerShape(10.dp) }
    Column(
        modifier = Modifier.width(236.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, accent.copy(alpha = 0.65f), shape)
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AeroButton(
            text = readI18n(if (playbackControls) "rwpp.replay.controls" else "rwpp.observer.controls"),
            subText = if (expanded) "▲" else "▼",
            accentColor = accent,
            clickSound = if (expanded) Sounds.closePopup else Sounds.openPopup,
            enabled = !AeroReplaySeekState.seeking,
            onClick = { expanded = !expanded }
        )
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val selectedIndex = teamOptions.indexOfFirst { it.teamId == selectedTeamId }.coerceAtLeast(0)
                AeroDropdownMenu(
                    label = readI18n("rwpp.replay.view"),
                    items = teamOptions.map { it.label },
                    selectedIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth(),
                    accentColor = accent,
                    onItemSelected = { index ->
                        val teamId = teamOptions.getOrNull(index)?.teamId ?: return@AeroDropdownMenu
                        GameScope.launch {
                            val engine = GameEngine.getInstance()
                            if (engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)) {
                                val team = if (teamId == Team.grayTeam.teamId) Team.grayTeam else Team.getTeam(teamId)
                                if (team != null) setAeroReplayPerspective(engine, team)
                            }
                        }
                    }
                )
                if (playbackControls) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AeroButton(
                        text = if (paused) {
                            readI18n("rwpp.replay.play")
                        } else {
                            readI18n("rwpp.replay.pause")
                        },
                        subText = if (paused) "▶" else "Ⅱ",
                        modifier = Modifier.weight(1f),
                        accentColor = accent,
                        clickSound = Sounds.select,
                        enabled = !AeroReplaySeekState.seeking,
                        onClick = {
                            GameScope.launch {
                                val engine = GameEngine.getInstance()
                                if (engine.replayEngine.isPlayingReplay) engine.replayEngine.togglePause()
                            }
                        }
                    )
                    AeroButton(
                        text = readI18n("rwpp.replay.speed"),
                        subText = if (playbackSpeed == 0f) "—" else "${playbackSpeed}×",
                        modifier = Modifier.weight(1f),
                        accentColor = accent,
                        clickSound = Sounds.swipe,
                        enabled = !AeroReplaySeekState.seeking,
                        onClick = {
                            GameScope.launch {
                                val engine = GameEngine.getInstance()
                                if (engine.replayEngine.isPlayingReplay) engine.replayEngine.cycleReplaySpeed()
                            }
                        }
                    )
                }
                if (playbackControls) AeroReplayTimelineControls(accent)
                val showAll = AeroReplayControlsState.showAllUnitOrders
                AeroButton(
                    text = readI18n("rwpp.replay.allVisibleUnitOrders"),
                    subText = if (showAll) "ON" else "OFF",
                    accentColor = if (showAll) Color(0xff72d8f5) else accent,
                    clickSound = Sounds.select,
                    onClick = { AeroReplayControlsState.showAllUnitOrders = !showAll }
                )
            }
        }
    }
}

private const val TEAM_OPTIONS_REFRESH_NANOS = 750_000_000L
