/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.server

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.NetworkCallbacks
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.server.ServerData
import io.github.rwpp.api.server.ServerInfoResponder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal desktop JVM host for the game's native headless multiplayer server.
 *
 * The owning host controls the loop and calls [tick] on its game thread. Networking, lockstep,
 * and simulation continue through the original engine frame and NetworkEngine paths.
 */
class NativeHeadlessServer(
    val options: Options = Options(),
) : AutoCloseable {
    data class Options(
        val port: Int = 5123,
        val map: String = DEFAULT_MAP,
        val name: String = "RWPP Server",
        val visible: Boolean = false,
        val maxPlayers: Int? = null,
        val transferMods: Boolean = false,
    ) {
        init {
            require(port in 1..65535) { "Server port must be between 1 and 65535" }
            require(name.isNotBlank()) { "Server player name cannot be blank" }
            require(maxPlayers == null || maxPlayers > 0) { "Maximum players must be positive" }
        }

        companion object {
            const val DEFAULT_MAP = "maps/skirmish/[p2]Lake (2p).tmx"
        }
    }

    private data class MapSelection(
        val path: String,
        val type: GameSetupMapType,
        val setupName: String,
        val nativePath: String?,
    )

    private val pendingNativeCallbacks = ConcurrentLinkedQueue<() -> Unit>()
    private val startCallbackQueued = AtomicBoolean(false)
    private val responderOwner = EventOwner()
    private val callbacks = object : NetworkCallbacks() {
        override fun startGameEvent() {
            if (startCallbackQueued.compareAndSet(false, true)) {
                pendingNativeCallbacks.add(::loadStartedGame)
            }
        }
    }

    private var engineValue: RWGameEngine? = null
    private var closed = false

    /** The initialized native game engine. Available after [start] returns. */
    val engine: RWGameEngine
        get() = checkNotNull(engineValue) { "The headless server has not started" }

    @Synchronized
    fun start() {
        check(engineValue == null) { "The headless server has already started" }
        check(!closed) { "The headless server is closed" }

        val created = HeadlessEngine.create()
        engineValue = created

        val network = created.networkEngine
        network.networkCallbacks = callbacks
        network.networkPort = options.port
        created.settings.networkPort = options.port
        network.playerName = network.sanitiseAndStorePlayerName(options.name)
        network.serverVisible = options.visible
        network.currentGameMapPath = null
        network.mapName = null
        applyMap(options.map, notifyClients = false)
        options.maxPlayers?.let { Team.setMaxTeamId(it, false) }

        check(network.startServer(true)) { "The native server could not listen on port ${options.port}" }
        appKoin.get<GameRoom>().option.canTransferMod = options.transferMods
        ServerInfoResponder.install(responderOwner, appKoin.get<Net>(), NativeServerInfo)
    }

    /**
     * The server-list entry this process publishes for itself.
     *
     * The same information answers the RWPP server-info query, so the console shows exactly what a
     * client would receive. Reads live room and mod state, so it runs on the game thread.
     */
    @Synchronized
    fun describeSelf(): ServerData = HeadlessServerData(
        ServerConfig("0.0.0.0:${options.port}", options.name, ServerType.Server, editable = false),
    ).apply { infoPacket = NativeServerInfo.describe() }

    /** Advances one original engine frame and then runs callbacks queued by that frame. */
    @Synchronized
    fun tick(deltaTime: Float = 1f, deltaMillis: Int = 16) {
        check(!closed) { "The headless server is closed" }
        val activeEngine = engine
        activeEngine.updateFrameInternal(deltaTime, deltaMillis)
        while (true) {
            val callback = pendingNativeCallbacks.poll() ?: break
            callback()
        }
    }

    /** Requests an original native multiplayer start after the selected map is prepared. */
    @Synchronized
    fun startMatch() {
        check(!closed) { "The headless server is closed" }
        val network = engine.networkEngine
        check(network.isServer) { "The native server is not hosting" }
        check(!network.gameStarted) { "A game is already in progress" }
        startCallbackQueued.set(false)
        appKoin.get<GameRoom>().startGame()
    }

    /** Selects a native map using the same path categories as the original multiplayer room. */
    @Synchronized
    fun changeMap(path: String) {
        check(!closed) { "The headless server is closed" }
        val network = engine.networkEngine
        check(!network.gameStarted) { "The map cannot be changed while a game is in progress" }
        applyMap(path, notifyClients = true)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pendingNativeCallbacks.clear()
        engineValue?.networkEngine?.disconnect("shutdownServer")
        responderOwner.close()
    }

    private fun applyMap(path: String, notifyClients: Boolean) {
        val activeEngine = engine
        val network = activeEngine.networkEngine
        val selected = parseMap(path)
        network.gameSetup.mapType = selected.type
        network.gameSetup.mapName = selected.setupName
        network.currentGameMapPath = selected.path
        network.mapName = selected.nativePath
        if (notifyClients && network.networked) {
            network.applyChangedSetup(network.gameSetup)
        }
    }

    /** Mirrors MainLauncher$3's native load path, without touching the desktop UI. */
    private fun loadStartedGame() {
        val activeEngine = engine
        val network = activeEngine.networkEngine
        if (!network.gameStarted) return

        activeEngine.inputNetStream = null
        when (network.gameSetup.mapType) {
            GameSetupMapType.savedGame -> activeEngine.gameSaver.loadSaveFile(network.gameSetup.mapName, true)
            GameSetupMapType.customMap, GameSetupMapType.skirmishMap -> {
                activeEngine.currentMapPath = checkNotNull(network.mapName) {
                    "No native map path was configured for the selected map"
                }
                activeEngine.loadMap(true, GameEngineMapType.normal)
            }
        }

        if (activeEngine.map?.mapLoaded == true) {
            network.startGameEventCompleted = true
            activeEngine.gamePaused = false
            activeEngine.touchInputDisabled = false
        } else {
            network.onStartGameFailed()
        }
        startCallbackQueued.set(false)
    }

    private fun parseMap(path: String): MapSelection {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized) {
            "Map must be a relative game path"
        }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Map path contains an invalid directory segment"
        }

        return when {
            normalized.startsWith(SKIRMISH_MAPS) -> MapSelection(
                path = normalized,
                type = GameSetupMapType.skirmishMap,
                setupName = normalized.removePrefix(SKIRMISH_MAPS),
                nativePath = normalized,
            )

            normalized.startsWith(CUSTOM_MAPS) -> MapSelection(
                path = normalized,
                type = GameSetupMapType.customMap,
                setupName = normalized.removePrefix(CUSTOM_MAPS),
                nativePath = normalized,
            )

            normalized.startsWith(SAVED_GAMES) -> MapSelection(
                path = normalized,
                type = GameSetupMapType.savedGame,
                setupName = normalized.removePrefix(SAVED_GAMES),
                nativePath = null,
            )

            else -> error("Map must be under maps/skirmish, mods/maps, or saves")
        }
    }

    private companion object {
        const val SKIRMISH_MAPS = "maps/skirmish/"
        const val CUSTOM_MAPS = "mods/maps/"
        const val SAVED_GAMES = "saves/"
    }
}
