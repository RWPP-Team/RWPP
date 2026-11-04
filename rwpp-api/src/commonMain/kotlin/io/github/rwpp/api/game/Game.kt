/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game

import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.game.map.MissionType
import io.github.rwpp.api.game.map.Replay
import kotlinx.coroutines.flow.Flow

/** The game service exposed by the host extension. */
interface Game {
    val gameRoom: GameRoom
    var state: GameState

    /** The latest answer supplied to the game's question callback, if one is pending. */
    val questionAnswer: String?

    fun startNewMissionGame(difficulty: Int, mission: Mission)
    suspend fun load(context: LoadingContext)
    fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean)
    fun hostNewSinglePlayer(sandbox: Boolean)
    fun setUserName(name: String)

    /**
     * Joins a remote room and returns success only after the native room handshake completes.
     * The caller must invoke [completeJoinServer] when it commits navigation into the room, or
     * [cancelJoinServer] if it abandons the join.
     */
    suspend fun directJoinServer(address: String, uuid: String?, context: LoadingContext): Result<String>
    fun cancelJoinServer()

    /** Commits a successful join when the caller is ready to navigate into the room. */
    fun completeJoinServer(): Boolean = false
    fun onQuestionCallback(option: String)

    /** Returns and clears the pending callback answer so a native prompt consumes it only once. */
    fun takeQuestionAnswer(): String?

    /** Applies the room cap on its authoritative host, including local skirmish rooms. */
    fun setTeamUnitCapHostGame(cap: Int)
    fun getAllMissionTypes(): List<MissionType>
    fun getAllMissions(): List<Mission>
    fun getMissionsByType(type: MissionType): List<Mission>
    fun getMissionPages(type: MissionType, pageSize: Int = 64): Flow<CatalogPage<Mission>>
    fun getAllMaps(flush: Boolean = false): List<GameMap>
    fun getAllMapsByMapType(mapType: GameSetupMapType): List<GameMap>

    /** Invalidates the complete map catalog without enumerating map or save files. */
    fun invalidateMapCatalog()

    /** Resolves the authoritative room map from the complete cache or its direct local path. */
    fun resolveSetupMap(type: GameSetupMapType, mapName: String?, currentMapPath: String?): GameMap

    /**
     * Enumerates maps in bounded batches. A type filter only enumerates that category and never
     * replaces the complete cache with a partial result. [refresh] invalidates the full cache first.
     */
    fun getMapPages(
        pageSize: Int = 64,
        refresh: Boolean = false,
        mapType: GameSetupMapType? = null,
    ): Flow<CatalogPage<GameMap>>

    fun getStartingUnitOptions(): List<Pair<Int, String>>
    fun getAllUnitTypes(): List<UnitTypeInterface>
    fun getAllReplays(): List<Replay>
    fun getReplayPages(pageSize: Int = 64, refresh: Boolean = false): Flow<CatalogPage<Replay>>

    /** Resolves an exact replay filename without enumerating the replay catalog. */
    fun getReplay(name: String): Replay?
    fun watchReplay(replay: Replay)
    fun isGameCouldContinue(): Boolean
    fun continueGame()
}
