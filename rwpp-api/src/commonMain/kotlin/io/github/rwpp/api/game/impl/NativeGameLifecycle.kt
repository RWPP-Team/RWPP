/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.State
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.event.events.TransportConnectedEvent
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.MapNames

/** Synchronizes the API game state and events when native menus bypass [Game] entry points. */
internal object NativeGameLifecycle {
    private val lifecycleLock = Any()
    private var lastPublishedMapKey: String? = null
    private var pendingJoinAddress: String? = null
    private var replayLoadInProgress = false
    private var replayMapLoadFailed = false
    private var sessionActive = false
    private var announcedHostKind: HostKind? = null
    private var joinedClientTeam: Team? = null
    private var connectedAddress: String? = null

    fun onMultiplayerHostStarted(network: NetworkEngine) {
        val engine = GameEngine.getInstance()
        val game = appKoin.get<Game>()
        synchronized(lifecycleLock) {
            pendingJoinAddress = null
            replayLoadInProgress = false
            sessionActive = true
        }
        game.state.multiplayer = true
        game.state.replay = false
        game.state.sandbox = false
        resetGameProgress(game)
        game.state.state = State.Menu

        if (announceHost(HostKind.Multiplayer)) GameEventPublisher.hostGame()
        publishMapChanged(network.gameSetup.mapName ?: network.currentGameMapPath, network.gameSetup.mapType)
        (network.localTeam ?: engine.playerTeam)?.let { GameEventPublisher.playerJoin(it) }
    }

    fun onSinglePlayerHostStarted(network: NetworkEngine) {
        val game = appKoin.get<Game>()
        game.state.multiplayer = false
        game.state.replay = false
        game.state.sandbox = network.sandboxMode
        resetGameProgress(game)
        game.state.state = State.Menu
        synchronized(lifecycleLock) { sessionActive = true }

        if (announceHost(HostKind.SinglePlayer)) GameEventPublisher.hostSinglePlayerGame()
        publishMapChanged(
            GameEngine.getInstance().currentMapPath ?: network.gameSetup.mapName,
            network.gameSetup.mapType,
        )
    }

    fun onSandboxModeStarted(network: NetworkEngine) {
        appKoin.get<Game>().state.sandbox = network.sandboxMode
    }

    fun onJoinAttempt(address: String) {
        val game = appKoin.get<Game>()
        synchronized(lifecycleLock) {
            pendingJoinAddress = address
            joinedClientTeam = null
            lastPublishedMapKey = null
            announcedHostKind = null
            replayLoadInProgress = false
        }
        game.state.multiplayer = false
        game.state.replay = false
        game.state.sandbox = false
        game.state.state = State.Menu
    }

    fun onClientConnected(network: NetworkEngine, fallbackAddress: String? = null) {
        val game = appKoin.get<Game>()
        game.state.multiplayer = network.networked && !network.singlePlayerServer
        game.state.replay = false
        game.state.sandbox = false
        resetGameProgress(game)
        game.state.state = State.Menu
        val address = synchronized(lifecycleLock) {
            announcedHostKind = null
            pendingJoinAddress.also { pendingJoinAddress = null }
        } ?: fallbackAddress
        synchronized(lifecycleLock) { connectedAddress = address; joinedClientTeam = null }
        synchronized(lifecycleLock) { sessionActive = true }
        broadcastNativeEvent(TransportConnectedEvent(network, address))
    }

    fun onClientRoomJoined(network: NetworkEngine, connection: Connection?) {
        val team = network.localTeam ?: return
        val address = synchronized(lifecycleLock) {
            if (joinedClientTeam != null) return
            joinedClientTeam = team
            connectedAddress.orEmpty()
        }
        GameEventPublisher.joinGame(address, network, connection, team)
    }

    fun onNetworkDisconnected(network: NetworkEngine, reason: String): Boolean {
        val endedSession = synchronized(lifecycleLock) {
            sessionActive.also { sessionActive = false }
        }
        if (!network.networked) {
            (appKoin.get<Game>().gameRoom as GameRoomImpl).resetRuntimeState()
        }
        if (endedSession) GameEventPublisher.disconnect(reason, network)
        if (network.networked) return endedSession
        val startingReplacementConnection = synchronized(lifecycleLock) {
            val keepPendingJoin = reason == "starting new" && pendingJoinAddress != null
            if (!keepPendingJoin) pendingJoinAddress = null
            announcedHostKind = null
            joinedClientTeam = null
            connectedAddress = null
            keepPendingJoin
        }
        val game = appKoin.get<Game>()
        game.state.multiplayer = false
        game.state.replay = GameEngine.getInstance().replayEngine.isPlayingReplay()
        game.state.sandbox = network.sandboxMode
        if (!startingReplacementConnection) onMenuShown()
        return endedSession
    }

    fun onReturnToBattleroom(network: NetworkEngine) {
        val game = appKoin.get<Game>()
        (game.gameRoom as? GameRoomImpl)?.restoreGeneratedMapSelection()
        val leavingGameplay = game.state.state == State.Playing || game.state.state == State.Paused
        game.state.multiplayer = network.networked && !network.singlePlayerServer
        game.state.replay = false
        game.state.sandbox = network.sandboxMode || GameEngine.getInstance().debugEditorMode
        game.state.state = State.Menu
        if (leavingGameplay) GameEventPublisher.quitGame()
    }

    fun onSetupApplied(network: NetworkEngine) {
        if (!network.isServer && !network.proxyController) return
        val configuredMap = network.gameSetup.mapName ?: return
        publishMapChanged(configuredMap, network.gameSetup.mapType)
    }

    fun onMapLoaded() {
        val engine = GameEngine.getInstance()
        if (engine.map?.mapLoaded != true) return
        val mapPath = engine.currentMapPath ?: return
        if (mapPath.startsWith("maps/menu_background/", ignoreCase = true)) return

        val game = appKoin.get<Game>()
        val replayLoading = synchronized(lifecycleLock) { replayLoadInProgress }
        syncModeFromEngine(game, engine.networkEngine)
        val trigger = engine.triggerEngine
        game.gameRoom.gameModes.observeNativeBase(when {
            trigger?.survivalMap == true -> "survival"
            trigger?.isSkirmish() == true -> "skirmish"
            else -> null
        })
        if (replayLoading) {
            game.state.replay = true
            game.state.multiplayer = false
            game.state.sandbox = false
            return // Replay may still fail after loading its initial map.
        }
        resetGameProgress(game)
        if (!game.state.multiplayer && !game.state.replay && announceHost(HostKind.SinglePlayer)) {
            GameEventPublisher.hostSinglePlayerGame()
        }
        game.state.state = State.Playing
        publishMapChanged(mapPath)
        GameEventPublisher.startGame()
    }

    fun onMapLoadFailed() {
        (appKoin.get<Game>().gameRoom as? GameRoomImpl)?.restoreGeneratedMapSelection()
        synchronized(lifecycleLock) {
            if (replayLoadInProgress) replayMapLoadFailed = true
        }
    }

    fun onReplayLoading() {
        synchronized(lifecycleLock) {
            replayLoadInProgress = true
            replayMapLoadFailed = false
            sessionActive = false
            pendingJoinAddress = null
            joinedClientTeam = null
            connectedAddress = null
            lastPublishedMapKey = null
            announcedHostKind = null
        }
    }

    fun onReplayLoaded(success: Boolean): Boolean {
        val engine = GameEngine.getInstance()
        val replayLoaded = synchronized(lifecycleLock) {
            replayLoadInProgress = false
            val result = success && !replayMapLoadFailed
            replayMapLoadFailed = false
            result
        }
        val game = appKoin.get<Game>()
        if (!replayLoaded) {
            engine.replayEngine.stop()
            game.state.multiplayer = engine.networkEngine.networked && !engine.networkEngine.singlePlayerServer
            game.state.replay = false
            game.state.sandbox = engine.networkEngine.sandboxMode || engine.debugEditorMode
            return false
        }
        game.state.multiplayer = false
        game.state.replay = true
        game.state.sandbox = false
        resetGameProgress(game)
        game.state.state = State.Playing
        publishMapChanged(engine.currentMapPath)
        GameEventPublisher.startGame()
        return true
    }

    fun onResume() {
        val game = appKoin.get<Game>()
        syncModeFromEngine(game, GameEngine.getInstance().networkEngine)
        game.state.state = State.Playing
    }

    fun onMenuShown() {
        val game = appKoin.get<Game>()
        val returnedFromGame = game.state.state == State.Playing || game.state.state == State.Paused
        game.state.state = State.Menu
        if (returnedFromGame) {
            synchronized(lifecycleLock) {
                lastPublishedMapKey = null
                announcedHostKind = null
            }
            GameEventPublisher.quitGame()
            GameEventPublisher.returnMainMenu()
        }
    }

    private fun syncModeFromEngine(game: Game, network: NetworkEngine) {
        val engine = GameEngine.getInstance()
        game.state.multiplayer = network.networked && !network.singlePlayerServer
        game.state.replay = engine.replayEngine.isPlayingReplay()
        game.state.sandbox = network.sandboxMode || engine.debugEditorMode
    }

    private fun resetGameProgress(game: Game) {
        game.state.gameOver = false
        game.state.defeatedPlayerSet.clear()
    }

    private fun announceHost(kind: HostKind): Boolean = synchronized(lifecycleLock) {
        if (announcedHostKind == kind) return@synchronized false
        announcedHostKind = kind
        true
    }

    private fun publishMapChanged(path: String?, mapType: GameSetupMapType? = null) {
        if (path.isNullOrBlank()) return
        val normalizedPath = normalizedMapPath(path, mapType)
        val mapKey = mapKey(normalizedPath)
        val shouldPublish = synchronized(lifecycleLock) {
            if (lastPublishedMapKey.equals(mapKey, ignoreCase = true)) {
                false
            } else {
                lastPublishedMapKey = mapKey
                true
            }
        }
        if (!shouldPublish) return
        GameEventPublisher.mapChanged(MapNames.getPrettyMapName(normalizedPath) ?: path)
    }

    private fun normalizedMapPath(path: String, mapType: GameSetupMapType?): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        if ('/' in normalized || mapType == null) return normalized
        val directory = when (mapType) {
            GameSetupMapType.skirmishMap -> "maps/skirmish"
            GameSetupMapType.customMap -> "mods/maps"
            GameSetupMapType.savedGame -> "saves"
        }
        return "$directory/$normalized"
    }

    private fun mapKey(path: String): String = normalizedMapPath(path, null).removeMapSuffix()

    private fun String.removeMapSuffix(): String = when {
        endsWith(".tmx", ignoreCase = true) -> dropLast(4)
        endsWith(".rwsave", ignoreCase = true) -> dropLast(7)
        else -> this
    }

    private enum class HostKind {
        Multiplayer,
        SinglePlayer,
    }
}
