/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.room

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.ai.AI
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.Game
import io.github.rwpp.appKoin

internal data class AeroPlayerRow(
    val player: Team, val slot: Int, val ally: Int, val name: String, val color: Int,
    val ping: String, val pingMs: Int, val ai: Boolean, val defeated: Boolean, val local: Boolean, val shared: Boolean,
)

internal data class AeroPlayerRoster(
    val rows: List<AeroPlayerRow> = emptyList(), val manage: Boolean = false,
    val host: Boolean = false, val sharedControl: Boolean = false,
)

internal fun snapshotAeroPlayers(engine: GameEngine): AeroPlayerRoster {
    val network = engine.networkEngine
    val rows = (0 until Team.totalTeamSlots).mapNotNull { Team.getTeam(it) }.map {
        AeroPlayerRow(
            it, it.teamId, it.allyTeam, it.name.orEmpty(), it.color, it.pingStatusText, it.ping,
            it.isAI, it.defeated, it === network.localTeam, it.sharedControlEnabled,
        )
    }
    val live = !engine.replayEngine.isPlayingReplay && (network.networked || network.singlePlayerServer)
    return AeroPlayerRoster(
        rows, live && (network.isServer || network.proxyController), live && network.isServer,
        network.gameSetup.sharedControl,
    )
}

internal fun groupAeroPlayers(rows: List<AeroPlayerRow>): List<Pair<Int, List<AeroPlayerRow>>> =
    rows.groupBy { it.ally }.entries.sortedBy { if (it.key == -3) Int.MAX_VALUE else it.key }
        .map { it.key to it.value.sortedBy(AeroPlayerRow::slot) }

internal enum class AeroPlayerAction { KICK, SURRENDER, SHARED_CONTROL, PAUSE_OR_RESUME_AI }

/** Revalidate authority and exact Team identity at execution, never trust a sampled slot number. */
internal fun performAeroPlayerAction(engine: GameEngine, target: Team, action: AeroPlayerAction): Boolean {
    val network = engine.networkEngine
    if (engine.replayEngine.isPlayingReplay || !(network.networked || network.singlePlayerServer) ||
        !(network.isServer || network.proxyController) || Team.getTeam(target.teamId) !== target
    ) return false
    when (action) {
        AeroPlayerAction.KICK -> {
            if (target === network.localTeam) return false
            network.kickTeamAndAttachedPlayer(target)
        }

        AeroPlayerAction.SURRENDER -> {
            if (!network.isServer || target.isSpectator || target.defeated) return false
            appKoin.get<Game>().gameRoom.sendSurrender(target)
        }

        AeroPlayerAction.SHARED_CONTROL -> {
            if (!network.isServer || target.isSpectator) return false
            target.sharedControlEnabled = !target.sharedControlEnabled
            network.markPlayerListDirty()
            network.updateTeamStatuses()
        }

        AeroPlayerAction.PAUSE_OR_RESUME_AI -> {
            if (network.isServer) {
                (target as? AI)?.run {
                    aiUpdatePaused = !aiUpdatePaused
                    return true
                }
            }

            return false
        }
    }
    return true
}
