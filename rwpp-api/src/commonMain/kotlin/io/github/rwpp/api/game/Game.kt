/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game

import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.game.map.MissionType
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.game.mode.GameModeSession

/** The game service exposed by the host extension. */
interface Game {
    val gameRoom: GameRoom
    /** The current game's composable rules, shared with its room configuration. */
    val gameModes: GameModeSession get() = gameRoom.gameModes
    var state: GameState
    /** The latest answer supplied to the game's question callback, if one is pending. */
    val questionAnswer: String?

    fun startNewMissionGame(difficulty: Int, mission: Mission)
    suspend fun load(context: LoadingContext)
    fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean)
    fun hostNewSinglePlayer(sandbox: Boolean)
    fun setUserName(name: String)
    suspend fun directJoinServer(address: String, uuid: String?, context: LoadingContext): Result<String>
    fun cancelJoinServer()
    fun onQuestionCallback(option: String)
    /** Returns and clears the pending callback answer so a native prompt consumes it only once. */
    fun takeQuestionAnswer(): String?
    fun setTeamUnitCapHostGame(cap: Int)
    fun getAllMissionTypes(): List<MissionType>
    fun getAllMissions(): List<Mission>
    fun getMissionsByType(type: MissionType): List<Mission>
    fun getAllMaps(flush: Boolean = false): List<GameMap>
    fun getAllMapsByMapType(mapType: GameSetupMapType): List<GameMap>
    fun getStartingUnitOptions(): List<Pair<Int, String>>
    fun getAllUnitTypes(): List<UnitTypeInterface>
    @Deprecated("Use gameRoom.gameModes.replace(BanUnitsMode(unitNames)).")
    fun onBanUnits(units: List<UnitTypeInterface>)
    fun getAllReplays(): List<Replay>
    fun watchReplay(replay: Replay)
    fun setEffectLimitForAllEffects(limit: Int)
    fun isGameCouldContinue(): Boolean
    fun continueGame()
}
