/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.team

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.GameRoom
import kotlin.random.Random

object TeamModes {
    val modes: MutableList<TeamMode> = mutableListOf(
        InternalTeamMode2t,
        InternalTeamMode3t,
        InternalTeamModeFFA,
        InternalTeamModeSpectators,
        RandomTeamMode,
        AllVsAI,
        AllVs2,
    )
}

sealed class InternalTeamMode(name: String) : TeamMode(name) {
    override fun onInit(gameRoom: GameRoom) = Unit
}

object InternalTeamMode2t : InternalTeamMode("2t") {
    override val displayName = "2 Teams (eg 5v5)"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) = targetSpawnPoint % 2
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) = Unit
}

object InternalTeamMode3t : InternalTeamMode("3t") {
    override val displayName = "3 Teams (eg 1v1v1)"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) = targetSpawnPoint % 3
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) =
        setOneBasedTeam(gameRoom, player, player.teamId % 3 + 1)
}

object InternalTeamModeFFA : InternalTeamMode("FFA") {
    override val displayName = "No teams (FFA)"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) = targetSpawnPoint
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) = setOneBasedTeam(gameRoom, player, player.teamId + 1)
}

object InternalTeamModeSpectators : InternalTeamMode("spectators") {
    override val displayName = "All spectators"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) = -3
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) = Unit
}

object RandomTeamMode : TeamMode("random-team") {
    override val displayName = "Random Team"
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) = Unit

    override fun onInit(gameRoom: GameRoom) {
        // Only the room authority assigns teams, and the seed comes from the synchronised room
        // setup. The default Random used to reshuffle teams differently in each process and on
        // every reconnect, so players returned to different teams after a resync.
        if (!gameRoom.isHost && !gameRoom.isHostServer) return
        val players = gameRoom.getPlayers().shuffled(Random(gameRoom.randomSeed.toLong()))
        val teamCount = when (players.size) {
            in 1..8 -> 2
            in 9..21 -> 3
            in 22..32 -> 4
            else -> 5
        }
        var leftCount = teamCount
        var currentTeam = 0
        players.forEach { player ->
            setAllyTeam(gameRoom, player, currentTeam)
            leftCount--
            if (leftCount == 0) {
                leftCount = teamCount
                currentTeam++
            }
        }
    }
}

object AllVsAI : TeamMode("all-vs-ai") {
    override val displayName = "All vs AI"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) = if (player.isAI) 2 else 1
    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) =
        setOneBasedTeam(gameRoom, player, if (player.isAI) 1 else 0)

    override fun onInit(gameRoom: GameRoom) {
        gameRoom.getPlayers().forEach { player -> setAllyTeam(gameRoom, player, if (player.isAI) 1 else 0) }
    }
}

object AllVs2 : TeamMode("all-vs-2") {
    override val displayName = "All vs 2 (survival)"
    override fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team) =
        if (player.teamId == 1) 1 else 0

    override fun onPlayerJoin(gameRoom: GameRoom, player: Team) =
        setOneBasedTeam(gameRoom, player, if (player.teamId == 1) 2 else 1)

    override fun onInit(gameRoom: GameRoom) {
        gameRoom.getPlayers().forEach { player -> setAllyTeam(gameRoom, player, if (player.teamId == 1) 1 else 0) }
    }
}

private fun setOneBasedTeam(gameRoom: GameRoom, player: Team, team: Int) =
    setAllyTeam(gameRoom, player, team - 1)

private fun setAllyTeam(gameRoom: GameRoom, player: Team, team: Int) {
    when {
        gameRoom.isHost -> player.allyTeam = team
        gameRoom.isHostServer -> GameEngine.getInstance().networkEngine.sendSetTeamCommand(player, team)
    }
}
