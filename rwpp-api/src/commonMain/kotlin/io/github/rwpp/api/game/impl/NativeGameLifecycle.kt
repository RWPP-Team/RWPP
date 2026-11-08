/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.State
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.event.events.MapLoadEvent
import io.github.rwpp.api.event.events.MapLoadPhase
import io.github.rwpp.api.event.events.TransportConnectedEvent
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import io.github.rwpp.api.event.hooks.hasNativeEventListeners
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.NetworkGameLoadingState
import io.github.rwpp.api.game.map.MapNames
import io.github.rwpp.api.game.map.defaultMapPath
import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.net.Socket

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
    private var clientRoomJoinTicket: ClientRoomJoinTicket? = null
    private val clientStartReadyAck = NetworkClientReadyAck()

    internal class ClientRoomJoinTicket {
        val roomReady = CompletableDeferred<Unit>()
        @Volatile
        var expectedSocket: Socket? = null
        private var redirectPending = false
        private var activePrompt: Any? = null
        private val promptChanges = Channel<Unit>(Channel.CONFLATED)

        @Synchronized
        fun promptOpened(prompt: Any) {
            if (roomReady.isCompleted) return
            activePrompt = prompt
            promptChanges.trySend(Unit)
        }

        @Synchronized
        fun promptClosed(prompt: Any) {
            if (activePrompt !== prompt) return
            activePrompt = null
            promptChanges.trySend(Unit)
        }

        @Synchronized
        private fun awaitingInput(): Boolean = activePrompt != null

        /** One network budget across socket and room handshake; human interaction has no deadline. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        suspend fun awaitConnectionReady(
            socketResult: Deferred<Result<Unit>>,
            timeoutMillis: Long,
            onSocketReady: () -> Unit = {}
        ): Boolean {
            var remainingNanos = timeoutMillis * 1_000_000L
            suspend fun <T : Any> step(result: Deferred<T>): T? {
                while (true) {
                    if (result.isCompleted) return result.await()
                    val waiting = awaitingInput()
                    if (!waiting && remainingNanos <= 0) return null
                    val started = System.nanoTime()
                    val outcome = select<JoinWait<T>> {
                        result.onAwait { JoinWait.Completed(it) }
                        promptChanges.onReceive { JoinWait.Changed }
                        if (!waiting) onTimeout((remainingNanos + 999_999L) / 1_000_000L) { JoinWait.TimedOut }
                    }
                    if (!waiting) remainingNanos -= System.nanoTime() - started
                    when (outcome) {
                        is JoinWait.Completed -> return outcome.value
                        JoinWait.TimedOut -> return null
                        JoinWait.Changed -> Unit
                    }
                }
            }

            val socket = step(socketResult) ?: return false
            socket.getOrThrow()
            onSocketReady()
            return step(roomReady) != null
        }

        @Synchronized
        fun beginNativeRedirect(connection: Connection?): Boolean {
            if (roomReady.isCompleted || !owns(connection)) return false
            redirectPending = true
            return true
        }

        @Synchronized
        fun acceptTransport(socket: Socket?): Boolean {
            if (socket == null) return false
            if (expectedSocket == null || expectedSocket === socket || redirectPending) {
                expectedSocket = socket
                redirectPending = false
                return true
            }
            return false
        }

        fun owns(connection: Connection?): Boolean {
            val expected = expectedSocket ?: return false
            var current = connection
            // Relay child connections retain the original transport through parent. Bound malformed cycles.
            repeat(32) {
                val candidate = current ?: return false
                if (candidate.socket === expected) return true
                current = candidate.parent
            }
            return false
        }

        fun completeFromConnection(connection: Connection?): Boolean =
            owns(connection) && roomReady.complete(Unit)

        fun completeFrom(socket: Socket?): Boolean {
            if (expectedSocket == null || expectedSocket !== socket) return false
            return roomReady.complete(Unit)
        }
    }

    private sealed interface JoinWait<out T> {
        data class Completed<T>(val value: T) : JoinWait<T>
        data object Changed : JoinWait<Nothing>
        data object TimedOut : JoinWait<Nothing>
    }

    fun onJoinPromptOpened(prompt: Any) {
        synchronized(lifecycleLock) { clientRoomJoinTicket?.promptOpened(prompt) }
    }

    fun onJoinPromptClosed(prompt: Any) {
        synchronized(lifecycleLock) { clientRoomJoinTicket?.promptClosed(prompt) }
    }

    fun beginClientRoomJoin(): ClientRoomJoinTicket = synchronized(lifecycleLock) {
        ClientRoomJoinTicket().also { clientRoomJoinTicket = it }
    }

    fun expectClientSocket(ticket: ClientRoomJoinTicket, socket: Socket): Boolean = synchronized(lifecycleLock) {
        if (clientRoomJoinTicket !== ticket || ticket.roomReady.isCompleted) return@synchronized false
        ticket.expectedSocket = socket
        true
    }

    fun finishClientRoomJoin(ticket: ClientRoomJoinTicket) {
        synchronized(lifecycleLock) {
            if (clientRoomJoinTicket === ticket) clientRoomJoinTicket = null
        }
    }

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
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
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

    fun onClientConnected(network: NetworkEngine, fallbackAddress: String? = null, socket: Socket? = null) {
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
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
        synchronized(lifecycleLock) {
            clientRoomJoinTicket?.let { ticket ->
                ticket.acceptTransport(socket)
            }
        }
        synchronized(lifecycleLock) { sessionActive = true }
        broadcastNativeEvent(TransportConnectedEvent(network, address))
    }

    fun onNativeRedirect(connection: Connection?) {
        synchronized(lifecycleLock) { clientRoomJoinTicket?.beginNativeRedirect(connection) }
    }

    fun onClientRoomJoined(network: NetworkEngine, connection: Connection?) {
        val team = network.localTeam ?: return
        var matchingTicket: ClientRoomJoinTicket? = null
        val address = synchronized(lifecycleLock) {
            if (joinedClientTeam != null) return
            joinedClientTeam = team
            matchingTicket = clientRoomJoinTicket?.takeIf { ticket ->
                ticket.owns(connection)
            }
            connectedAddress.orEmpty()
        }
        try {
            GameEventPublisher.joinGame(address, network, connection, team)
        } finally {
            try {
                // Client packet 115 has populated the local team and player list. The normal
                // desktop updateUI hook is server-only, so explicitly refresh room consumers.
                GameEventPublisher.refreshUi()
            } finally {
                matchingTicket?.completeFromConnection(connection)
            }
        }
    }

    fun onNetworkDisconnected(network: NetworkEngine, reason: String): Boolean {
        // A truncated or failed map transfer ends the socket while the client still waits for the
        // map. Report it before the loading state is cleared, otherwise the client returns to the
        // menu without any explanation.
        val interruptedMapLoad = NetworkGameLoadingState.isLoadingMap || clientStartReadyAck.isPending()
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
        if (interruptedMapLoad && network.networked && !network.isServer) {
            reportInterruptedMapTransfer(reason)
        }
        val endedSession = synchronized(lifecycleLock) {
            sessionActive.also { sessionActive = false }
        }
        if (!network.networked) {
            (appKoin.get<Game>().gameRoom as GameRoomImpl).resetRuntimeState()
            resetNativeRoomMap(network)
        }
        if (endedSession) GameEventPublisher.disconnect(reason, network)
        if (network.networked) return endedSession
        var interruptedJoin: ClientRoomJoinTicket? = null
        val startingReplacementConnection = synchronized(lifecycleLock) {
            val keepPendingJoin = reason == "starting new" && pendingJoinAddress != null
            if (!keepPendingJoin) {
                pendingJoinAddress = null
                interruptedJoin = clientRoomJoinTicket
                clientRoomJoinTicket = null
            }
            announcedHostKind = null
            joinedClientTeam = null
            connectedAddress = null
            keepPendingJoin
        }
        interruptedJoin?.roomReady?.completeExceptionally(
            IOException(reason),
        )
        val game = appKoin.get<Game>()
        game.state.multiplayer = false
        game.state.replay = GameEngine.getInstance().replayEngine.isPlayingReplay
        game.state.sandbox = network.sandboxMode
        if (!startingReplacementConnection) onMenuShown()
        return endedSession
    }

    fun onReturnToBattleroom(network: NetworkEngine) {
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
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

    fun onServerInfoReceived(network: NetworkEngine) {
        if (!network.networked || network.isServer || !network.receivedServerInfo) return
        val mapName = network.gameSetup.mapName ?: return
        network.currentGameMapPath = defaultMapPath(mapName, network.gameSetup.mapType)
        publishMapChanged(network.gameSetup.mapName, network.gameSetup.mapType)
        GameEventPublisher.refreshUi()
    }

    fun onNetworkStateReset(network: NetworkEngine, keepTeams: Boolean) {
        if (keepTeams) return // Native single-player preparation deliberately retains its room.
        resetNativeRoomMap(network)
        synchronized(lifecycleLock) { lastPublishedMapKey = null }
    }

    fun onMapLoaded() {
        val engine = GameEngine.getInstance()
        if (engine.map?.mapLoaded != true) return
        if (engine.networkEngine.networked && !engine.networkEngine.isServer && engine.networkEngine.gameStarted) {
            clientStartReadyAck.markMapLoaded()
        }
        val mapPath = engine.currentMapPath ?: return
        if (mapPath.startsWith("maps/menu_background/", ignoreCase = true)) return

        val game = appKoin.get<Game>()
        val replayLoading = synchronized(lifecycleLock) { replayLoadInProgress }
        syncModeFromEngine(game, engine.networkEngine)
        val trigger = engine.triggerEngine
        game.gameRoom.gameModes.observeNativeBase(
            when {
                trigger?.survivalMap == true -> "survival"
                trigger?.isSkirmish() == true -> "skirmish"
                else -> null
            }
        )
        if (replayLoading) {
            game.state.replay = true
            game.state.multiplayer = false
            game.state.sandbox = false
            return // Replay may still fail after loading its initial map.
        }
        if (game.state.multiplayer && engine.networkEngine.gameStarted) {
            synchronized(lifecycleLock) {
                clientRoomJoinTicket?.completeFromConnection(engine.networkEngine.clientConnection)
            }
            // Transfer ownership before publishing Playing: disposing the join page must never
            // cancel a live verification map, even if its lobby coroutine has not resumed yet.
            GamePlatform.completeJoinServer()
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
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
        (appKoin.get<Game>().gameRoom as? GameRoomImpl)?.restoreGeneratedMapSelection()
        synchronized(lifecycleLock) {
            if (replayLoadInProgress) replayMapLoadFailed = true
        }
    }

    /**
     * Packet 120 carries the map inline. When a server fails mid-transfer the socket closes while
     * the client still waits for the map, and the native client only logs the read failure. Publish
     * the same failure event as a local map-load error so the client can tell the player.
     */
    private fun reportInterruptedMapTransfer(reason: String) {
        if (!hasNativeEventListeners(MapLoadEvent::class)) return
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return
        runCatching {
            broadcastNativeEvent(
                MapLoadEvent(
                    engine = engine,
                    path = engine.currentMapPath,
                    type = com.corrodinggames.rts.gameFramework.GameEngineMapType.normal,
                    loadAi = false,
                    keepTeams = false,
                    phase = MapLoadPhase.FAILED,
                    error = IOException("Map transfer interrupted: $reason"),
                ),
            )
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
        game.state.replay = engine.replayEngine.isPlayingReplay
        game.state.sandbox = network.sandboxMode || engine.debugEditorMode
    }

    private fun resetGameProgress(game: Game) {
        game.state.gameOver = false
        game.state.defeatedPlayerSet.clear()
    }

    /** Called after native packet 120 has initialized the client's network game. */
    fun onClientNetworkGameStarting(network: NetworkEngine) {
        val isNetworkedClient = network.networked && !network.isServer
        clientStartReadyAck.begin(isNetworkedClient)
        if (isNetworkedClient) NetworkGameLoadingState.beginMapLoad()
        else NetworkGameLoadingState.finishMapLoad()
    }

    /** Send the native ready packet only once the map has loaded and the simulation loop is active. */
    fun acknowledgeClientReadyAfterMapLoad(engine: GameEngine) {
        if (!clientStartReadyAck.isPending()) return
        val network = engine.networkEngine
        val isNetworkedClient = network.networked && !network.isServer
        if (!clientStartReadyAck.beginAttemptIfReady(
                isNetworkedClient = isNetworkedClient,
                gameStarted = network.gameStarted,
                engineMapLoaded = engine.map?.mapLoaded == true,
                gameLoopActive = engine.gameLoopActive,
            )
        ) return

        val failure = runCatching { network.sendClientReadyState() }.exceptionOrNull()
        if (failure == null) {
            clientStartReadyAck.markSent()
            // The loading overlay must clear on the first successful send; later resends only
            // confirm the ready flag to the host.
            NetworkGameLoadingState.finishMapLoad()
        } else if (!clientStartReadyAck.markAttemptFailed()) {
            logger().warn("Could not send the post-load client-ready packet", failure)
            NetworkGameLoadingState.finishMapLoad()
        }
    }

    fun onNativeNetworkStartFailed() {
        clientStartReadyAck.clear()
        NetworkGameLoadingState.finishMapLoad()
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
