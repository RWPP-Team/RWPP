/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.room

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.team.TeamMode
import io.github.rwpp.config.RoomPreset
import io.github.rwpp.config.RoomPresetPlayer

internal fun findPresetMap(preset: RoomPreset, maps: List<GameMap>): GameMap? = maps.singleOrNull {
    it.mapType.name == preset.mapType && it.enginePath.replace('\\', '/')
        .equals(preset.mapPath.replace('\\', '/'), true)
}

/** Applies recorded slots to current and later players, without changing their spawn positions. */
internal class PresetTeamMode(private val players: List<RoomPresetPlayer>) : TeamMode("room-preset") {
    override val displayName: String get() = "Room preset"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team): Int =
        players.firstOrNull { it.slot == targetSpawnPoint }?.team ?: player.allyTeam

    override fun onInit(gameRoom: GameRoom) {
        gameRoom.getPlayers().forEach { onPlayerJoin(gameRoom, it) }
    }

    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) {
        val saved = players.firstOrNull { it.slot == player.teamId } ?: return
        if (gameRoom.isHost) {
            player.allyTeam = saved.team
            player.colorOverride = saved.color
            player.startingUnitsOverride = saved.startingUnit
        } else if (gameRoom.isHostServer) {
            GameEngine.getInstance().networkEngine.sendSetTeamCommand(player, saved.team)
        }
    }
}
