/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.command.GameCommandRuntime
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.map.XMLMap
import io.github.rwpp.api.game.team.TeamMode
import io.github.rwpp.api.game.mode.GameModeSession

interface GameRoom {
    var maxPlayerCount: Int
    val isHost: Boolean
    val isHostServer: Boolean
    val localPlayer: Team
    var sharedControl: Boolean
    val randomSeed: Int
    val mapType: GameSetupMapType
    var selectedMap: GameMap
    var displayMapName: String
    var startingCredits: Int
    var startingUnits: Int
    var fogMode: Int
    var revealedMap: Boolean
    var aiDifficulty: Int
    var incomeMultiplier: Float
    var noNukes: Boolean
    var allowSpectators: Boolean
    var lockedRoom: Boolean
    var teamLock: Boolean

    var teamMode: TeamMode?
    /** Independently composable gameplay rules owned by this room. */
    val gameModes: GameModeSession
    @Deprecated("Use gameModes.replace(GameSpeedMode(multiplier)).")
    var gameSpeed: Float
    @Deprecated("Use gameModes.activate(MapTransformMode(id, transformer)).")
    var gameMapTransformer: ((XMLMap) -> Unit)?
    var option: RoomOption
    val isConnecting: Boolean

    fun getPlayers(): List<Team>
    fun getAllPlayers(): Array<Team?>
    fun getPlayerByClient(client: Connection): Team? = getPlayers().firstOrNull {
        GameEngineAccess.connectionFor(it) === client
    }

    suspend fun roomDetails(): String

    fun sendChatMessage(message: String)
    fun sendSystemMessage(message: String)
    fun sendQuickGameCommand(command: String)

    fun pauseOrResumeGame(pause: Boolean)

    fun sendMessageToPlayer(player: Team?, title: String?, message: String, color: Int = -1)
    fun sendSurrender(player: Team)

    fun spawnUnit(player: Team, unitType: UnitTypeInterface, x: Float, y: Float, size: Int = 0)
    fun syncAllPlayer()

    fun addAI(count: Int = 1)
    fun applyRoomConfig(
        maxPlayerCount: Int,
        sharedControl: Boolean,
        startingCredits: Int,
        startingUnits: Int,
        fogMode: Int,
        aiDifficulty: Int,
        incomeMultiplier: Float,
        noNukes: Boolean,
        allowSpectators: Boolean,
        teamLock: Boolean,
        teamMode: TeamMode?,
    )

    fun remainingPlayersCount(): Int
    fun kickPlayer(player: Team)
    fun disconnect(reason: String = "excited")
    fun updateUI()
    fun startGame()
}

/** The network engine owns the team-to-connection lookup. */
private object GameEngineAccess {
    fun connectionFor(team: Team): Connection? =
        GameEngine.getInstance().networkEngine.getConnectionForTeam(team)
}

const val commandPrefix: String = "/"

fun GameRoom.sendChatMessageOrCommand(message: String, title: String = "RWML") {
    if (GameCommandRuntime.executeLocalInput(this, message, title) == null) sendChatMessage(message)
}
