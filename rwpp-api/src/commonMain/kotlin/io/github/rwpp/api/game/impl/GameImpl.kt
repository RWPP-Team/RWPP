/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.State
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.GameState
import io.github.rwpp.api.game.LoadingContext
import io.github.rwpp.api.game.mode.BanUnitsMode
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.game.map.MissionType
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** The shared game service. Native file and screen operations are implemented by the API source sets. */
@Single(binds = [Game::class])
class GameImpl(
    override val gameRoom: GameRoom,
) : Game {
    override var state: GameState = GameState()

    private var missionsCache: List<Mission>? = null
    private var mapsCache: List<GameMap>? = null
    private var mapsByType: Map<GameSetupMapType, List<GameMap>> = emptyMap()
    private var replaysCache: List<Replay>? = null

    /** The latest answer provided by the game's native question dialog. */
    override var questionAnswer: String? = null
        private set

    override suspend fun load(context: LoadingContext) {
        context.message("Preparing game interface…")
        GamePlatform.initializeMap()
    }

    override fun startNewMissionGame(difficulty: Int, mission: Mission) {
        state.multiplayer = false
        state.replay = false
        state.sandbox = false
        GameEngine.getInstance().settings.aiDifficulty = difficulty
        val path = "maps/${mission.type.pathName()}/${mission.mapName}${mission.getMapSuffix()}"
        GameScope.launch {
            runCatching {
                GameEngine.getInstance().networkEngine.disconnect("starting singleplayer")
                GamePlatform.loadMission(path, sandbox = false)
                state.state = State.Playing
                GamePlatform.openGameScreen()
            }.onFailure { logger().error("Could not start mission ${mission.name}", it) }
        }
    }

    override fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean) {
        val engine = GameEngine.getInstance()
        val network = engine.networkEngine
        network.disconnect("starting new")
        GamePlatform.initializeMap(force = true)
        network.serverPassword = password
        network.modsEnabled = useMods
        network.serverVisible = isPublic

        GameScope.launch {
            if (!network.startServer(false)) return@launch
            state.state = State.Menu
            GameEventPublisher.refreshUi()
        }
    }

    override fun hostNewSinglePlayer(sandbox: Boolean) {
        val engine = GameEngine.getInstance()
        engine.settings.aiDifficulty = 1
        engine.networkEngine.disconnect("starting singleplayer")
        Team.clearAllTeams()
        engine.networkEngine.playerName = "You"
        engine.networkEngine.modsEnabled = true
        GamePlatform.initializeMap(force = true)

        GameScope.launch {
            runCatching {
                GamePlatform.startSinglePlayer(DEFAULT_SKIRMISH_MAP, sandbox)
                state.multiplayer = false
                state.sandbox = sandbox
                state.replay = false
                state.gameOver = false
                state.defeatedPlayerSet.clear()
                state.state = State.Playing
                GamePlatform.openGameScreen()
                GameEventPublisher.refreshUi()
            }.onFailure { logger().error("Could not start a new single-player game", it) }
        }
    }

    override fun setUserName(name: String) {
        GameEngine.getInstance().networkEngine.sanitiseAndStorePlayerName(name)
    }

    override suspend fun directJoinServer(
        address: String,
        uuid: String?,
        context: LoadingContext,
    ): Result<String> {
        GamePlatform.initializeMap()
        return GamePlatform.directJoinServer(address, uuid, context)
    }

    override fun cancelJoinServer() = GamePlatform.cancelJoinServer()

    override fun onQuestionCallback(option: String) {
        questionAnswer = option
    }

    override fun takeQuestionAnswer(): String? = questionAnswer.also { questionAnswer = null }

    override fun setTeamUnitCapHostGame(cap: Int) {
        val engine = GameEngine.getInstance()
        engine.networkEngine.teamUnitCap = cap
        engine.networkEngine.teamUnitCapMax = cap
        engine.unitsMaxCap = cap
    }

    override fun getAllMissionTypes(): List<MissionType> =
        listOf(MissionType.Normal, MissionType.Challenge, MissionType.Survival)

    override fun getAllMissions(): List<Mission> = missionsCache ?: GamePlatform.getAllMissions(getAllMissionTypes())
        .also { missionsCache = it }

    override fun getMissionsByType(type: MissionType): List<Mission> = getAllMissions().filter { it.type == type }

    override fun getAllMaps(flush: Boolean): List<GameMap> {
        if (flush) {
            mapsCache = null
            mapsByType = emptyMap()
        }
        mapsCache?.let { return it }
        val maps = GamePlatform.getAllMaps()
        mapsByType = maps
        return maps.values.flatten().also { mapsCache = it }
    }

    override fun getAllMapsByMapType(mapType: GameSetupMapType): List<GameMap> {
        if (mapsCache == null) getAllMaps()
        return mapsByType[mapType].orEmpty()
    }

    override fun getStartingUnitOptions(): List<Pair<Int, String>> {
        val network = GameEngine.getInstance().networkEngine
        return network.startingUnitOptions.mapNotNull { option ->
            (option as? Int)?.let { it to network.getStartingUnitsName(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getAllUnitTypes(): List<UnitTypeInterface> = UnitType.allTypes as List<UnitTypeInterface>

    override fun onBanUnits(units: List<UnitTypeInterface>) {
        if (units.isEmpty()) gameRoom.gameModes.deactivate(BanUnitsMode.ID)
        else gameRoom.gameModes.replace(BanUnitsMode(units.map(UnitTypeInterface::getInternalName).toSet()))
        if (units.isNotEmpty()) {
            gameRoom.sendSystemMessage(
                "Host has banned these units (房间已经ban以下单位): ${units.joinToString(", ") { it.getDisplayName() }}",
            )
        }
    }

    override fun getAllReplays(): List<Replay> = replaysCache ?: GamePlatform.getAllReplays().also { replaysCache = it }

    override fun watchReplay(replay: Replay) {
        GameScope.launch {
            val loaded = runCatching {
                GameEngine.getInstance().replayEngine.loadReplay(replay.name)
            }.getOrDefault(false)
            if (!loaded) {
                logger().warn("Could not load replay ${replay.name}")
                return@launch
            }
            GamePlatform.openGameScreen()
        }
    }

    override fun setEffectLimitForAllEffects(limit: Int) {
        GameEngine.getInstance().effectEngine.apply {
            limitVerylow = limit
            limitLow = limit
            limitHigh = limit
            limitVeryhigh = limit
        }
    }

    override fun isGameCouldContinue(): Boolean {
        val engine = GameEngine.getInstance()
        val path = engine.currentMapPath
        return engine.gameLoopActive && !engine.gameEnded && (path == null || !path.startsWith("maps/menu_background/"))
    }

    override fun continueGame() {
        if (!isGameCouldContinue()) return
        NativeGameLifecycle.onResume()
        state.state = State.Playing
        GamePlatform.continueGame()
    }

    private companion object {
        const val DEFAULT_SKIRMISH_MAP = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
    }
}
