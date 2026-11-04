/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
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
import io.github.rwpp.api.game.*
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Mission
import io.github.rwpp.api.game.map.MissionType
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    private var mapsLookupIndex: RoomMapCatalogIndex? = null
    private var replaysCache: List<Replay>? = null
    private val catalogLock = Any()
    private var mapCatalogGeneration = 0L
    private var replayCatalogGeneration = 0L

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
        GameScope.launch {
            runCatching {
                val engine = GameEngine.getInstance()
                val network = engine.networkEngine
                network.disconnect("starting singleplayer")
                engine.debugEditorMode = false
                Team.clearAllTeams()
                val player = com.corrodinggames.rts.game.Player(0)
                player.name = engine.settings.lastNetworkPlayerName ?: "Player"
                player.allyTeam = 0
                engine.playerTeam = player
                network.playerName = player.name
                network.modsEnabled = true
                check(if (sandbox) network.startSandboxMode() else network.startSingleplayerServer()) {
                    "Could not create the single-player room"
                }
                setTeamUnitCapHostGame(engine.settings.teamUnitCapHostedGame)
                GamePlatform.initializeMap(force = true)
                network.gameSetup.aiDifficulty = engine.settings.aiDifficulty
                network.localTeam = player
                network.addAIToGame()
                state.multiplayer = false
                state.sandbox = sandbox
                state.replay = false
                state.gameOver = false
                state.defeatedPlayerSet.clear()
                // Preparing a room must not load a map or publish gameplay entry.
                state.state = State.Menu
                GameEventPublisher.refreshUi()
            }.onFailure { logger().error("Could not prepare a single-player room", it) }
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

    override fun completeJoinServer() = GamePlatform.completeJoinServer()

    override fun onQuestionCallback(option: String) {
        questionAnswer = option
    }

    override fun takeQuestionAnswer(): String? = questionAnswer.also { questionAnswer = null }

    override fun setTeamUnitCapHostGame(cap: Int) {
        val engine = GameEngine.getInstance()
        val network = engine.networkEngine
        when (roomUnitCapTarget(network.networked, network.singlePlayerServer, network.isServer)) {
            RoomUnitCapTarget.SinglePlayerHost -> {
                // Native loadMap initializes local skirmishes from this setting before it checks
                // network caps. A single-player server therefore needs the hosted-room value here.
                engine.settings.teamUnitCapSinglePlayer = cap
                network.teamUnitCap = cap
                network.teamUnitCapMax = cap
                engine.unitsMaxDefault = cap
                engine.unitsMaxCap = cap
            }

            RoomUnitCapTarget.MultiplayerHost -> {
                network.teamUnitCap = cap
                network.teamUnitCapMax = cap
                engine.unitsMaxCap = cap
            }

            RoomUnitCapTarget.None -> Unit
        }
    }

    override fun getAllMissionTypes(): List<MissionType> =
        listOf(MissionType.Normal, MissionType.Challenge, MissionType.Survival)

    override fun getAllMissions(): List<Mission> = missionsCache ?: GamePlatform.getAllMissions(getAllMissionTypes())
        .also { missionsCache = it }

    override fun getMissionsByType(type: MissionType): List<Mission> = getAllMissions().filter { it.type == type }

    override fun getMissionPages(type: MissionType, pageSize: Int): Flow<CatalogPage<Mission>> {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        return flow {
            val cached = missionsCache?.filter { it.type == type }
            if (cached != null) {
                var pageIndex = 0
                for (start in cached.indices step pageSize) {
                    currentCoroutineContext().ensureActive()
                    val end = (start + pageSize).coerceAtMost(cached.size)
                    emit(CatalogPage(pageIndex++, cached.subList(start, end).toList()))
                }
            } else {
                GamePlatform.getMissionPages(listOf(type), pageSize).collect { emit(it) }
            }
        }.flowOn(Dispatchers.IO).buffer(1)
    }

    override fun getAllMaps(flush: Boolean): List<GameMap> {
        if (flush) invalidateMapCatalog()
        val generation = synchronized(catalogLock) {
            mapsCache?.let { return it }
            mapCatalogGeneration
        }
        val maps = GamePlatform.getAllMaps()
        val flattened = maps.values.flatten()
        synchronized(catalogLock) {
            if (generation == mapCatalogGeneration && mapsCache == null) {
                mapsByType = maps
                mapsCache = flattened
                mapsLookupIndex = RoomMapCatalogIndex(flattened)
            }
        }
        return flattened
    }

    override fun invalidateMapCatalog() {
        synchronized(catalogLock) {
            mapsCache = null
            mapsByType = emptyMap()
            mapsLookupIndex = null
            mapCatalogGeneration++
        }
    }

    override fun resolveSetupMap(
        type: GameSetupMapType,
        mapName: String?,
        currentMapPath: String?,
    ): GameMap {
        val cachedMatch = synchronized(catalogLock) {
            if (mapsCache != null) mapsLookupIndex?.find(type, mapName, currentMapPath) else null
        }
        cachedMatch?.let { return it }
        val directMaps = GamePlatform.getDirectSetupMaps(type, mapName, currentMapPath)
        return resolveRoomMap(directMaps, type, mapName, currentMapPath)
    }

    override fun getMapPages(
        pageSize: Int,
        refresh: Boolean,
        mapType: GameSetupMapType?,
    ): Flow<CatalogPage<GameMap>> {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        return flow {
            if (refresh) invalidateMapCatalog()
            val generation = synchronized(catalogLock) { mapCatalogGeneration }
            val cached = synchronized(catalogLock) { mapsCache }
            if (cached != null) {
                val matching = if (mapType == null) cached else cached.filter { it.mapType == mapType }
                var pageIndex = 0
                for (start in matching.indices step pageSize) {
                    currentCoroutineContext().ensureActive()
                    val end = (start + pageSize).coerceAtMost(matching.size)
                    emit(CatalogPage(pageIndex++, matching.subList(start, end).toList()))
                }
                return@flow
            }

            val collected = if (mapType == null) ArrayList<GameMap>() else null
            GamePlatform.getMapPages(pageSize, mapType).collect { page ->
                currentCoroutineContext().ensureActive()
                collected?.addAll(page.items)
                emit(page)
            }
            if (collected != null) {
                synchronized(catalogLock) {
                    if (generation == mapCatalogGeneration && mapsCache == null) {
                        mapsCache = collected.toList()
                        mapsByType = collected.groupBy { it.mapType }
                        mapsLookupIndex = RoomMapCatalogIndex(collected)
                    }
                }
            }
        }.flowOn(Dispatchers.IO).buffer(1)
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

    override fun getAllReplays(): List<Replay> {
        val generation = synchronized(catalogLock) {
            replayCatalogGeneration++
            replaysCache = null
            replayCatalogGeneration
        }
        val replays = GamePlatform.getAllReplays()
        synchronized(catalogLock) {
            if (generation == replayCatalogGeneration) replaysCache = replays
        }
        return replays
    }

    override fun getReplayPages(pageSize: Int, refresh: Boolean): Flow<CatalogPage<Replay>> {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        return flow {
            val generation = synchronized(catalogLock) {
                if (refresh) {
                    replaysCache = null
                    replayCatalogGeneration++
                }
                replayCatalogGeneration
            }
            val cached = synchronized(catalogLock) { replaysCache }
            if (cached != null) {
                var pageIndex = 0
                for (start in cached.indices step pageSize) {
                    currentCoroutineContext().ensureActive()
                    val end = (start + pageSize).coerceAtMost(cached.size)
                    emit(CatalogPage(pageIndex++, cached.subList(start, end).toList()))
                }
                return@flow
            }

            val collected = ArrayList<Replay>()
            GamePlatform.getReplayPages(pageSize).collect { page ->
                currentCoroutineContext().ensureActive()
                collected.addAll(page.items)
                emit(page)
            }
            synchronized(catalogLock) {
                if (generation == replayCatalogGeneration && replaysCache == null) {
                    replaysCache = collected.toList()
                }
            }
        }.flowOn(Dispatchers.IO).buffer(1)
    }

    override fun getReplay(name: String): Replay? = GamePlatform.getReplay(name)

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

internal enum class RoomUnitCapTarget { SinglePlayerHost, MultiplayerHost, None }

internal fun roomUnitCapTarget(
    networked: Boolean,
    singlePlayerServer: Boolean,
    isServer: Boolean,
): RoomUnitCapTarget = when {
    !networked || !isServer -> RoomUnitCapTarget.None
    singlePlayerServer -> RoomUnitCapTarget.SinglePlayerHost
    else -> RoomUnitCapTarget.MultiplayerHost
}
