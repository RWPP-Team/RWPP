/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.TeamLayout
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.State
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.GameRoom
import io.github.rwpp.api.game.GameState
import io.github.rwpp.api.game.RoomOption
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.MapNames
import io.github.rwpp.api.game.mode.GameModeSession
import io.github.rwpp.api.game.mode.GameModes
import io.github.rwpp.api.game.mode.GameSpeedMode
import io.github.rwpp.api.game.mode.MapTransformMode
import io.github.rwpp.api.game.team.TeamMode
import io.github.rwpp.api.map.XMLMap
import io.github.rwpp.api.net.packets.GamePacket
import io.github.rwpp.api.net.sendPacketToClient
import io.github.rwpp.api.util.Reflect
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.io.File

/**
 * Shared room behavior over the engine's network API.
 *
 * Server/proxy setup, map selection, room messages, and network commands use the native engine API.
 */
@Single(binds = [GameRoom::class])
class GameRoomImpl : GameRoom {
    private val engine get() = GameEngine.getInstance()
    private val network get() = engine.networkEngine
    private val setup get() = network.gameSetup
    private val game get() = appKoin.get<Game>()

    private fun updateSetup(change: (GameSetup) -> Unit) {
        when {
            network.isServer -> {
                val current = network.gameSetup
                change(current)
                network.applyChangedSetup(current)
            }

            network.proxyController -> {
                val pending = network.getChangeableSetup()
                change(pending)
                network.applyChangedSetup(pending)
            }
        }
    }

    override var maxPlayerCount: Int
        get() = Team.maxTeamId
        set(value) {
            if (isHost && maxPlayerCount != value) Team.setMaxTeamId(value, true)
        }

    override val isHost: Boolean get() = network.isServer
    override val isHostServer: Boolean get() = network.proxyController
    override val localPlayer: Team get() = network.localTeam ?: engine.playerTeam ?: Team.blankPlayer

    override var sharedControl: Boolean
        get() = setup.sharedControl
        set(value) = updateSetup { it.sharedControl = value }

    override val randomSeed: Int get() = setup.randomSeed
    override val mapType: GameSetupMapType get() = setup.mapType

    override var selectedMap: GameMap
        get() {
            val currentPath = network.currentGameMapPath
            val configuredName = setup.mapName
            // A received setup is authoritative even when that map is unavailable locally.
            return game.resolveSetupMap(setup.mapType, configuredName, currentPath)
        }
        set(value) {
            if (!isHost && !isHostServer) return
            restoreGeneratedMapSelection()
            updateSetup {
                it.mapType = value.mapType
                it.mapName = value.mapName + value.getMapSuffix()
            }
            network.currentGameMapPath = value.enginePath
            updateUI()
        }

    override var displayMapName: String
        get() = MapNames.getPrettyMapName(setup.mapName)
        set(value) = updateSetup { it.mapName = value }

    override var startingCredits: Int
        get() = setup.startingCredits
        set(value) = updateSetup { it.startingCredits = value }

    override var startingUnits: Int
        get() = setup.startingUnits
        set(value) = updateSetup { it.startingUnits = value }

    override var fogMode: Int
        get() = setup.fogMode
        set(value) = updateSetup { it.fogMode = value }

    override var revealedMap: Boolean
        get() = setup.revealedMap
        set(value) = updateSetup { it.revealedMap = value }

    override var aiDifficulty: Int
        get() = setup.aiDifficulty
        set(value) = updateSetup { it.aiDifficulty = value }

    override var incomeMultiplier: Float
        get() = setup.incomeMultiplier
        set(value) = updateSetup { it.incomeMultiplier = value }

    override var noNukes: Boolean
        get() = setup.noNukes
        set(value) = updateSetup { it.noNukes = value }

    override var allowSpectators: Boolean
        get() = setup.allowSpectators
        set(value) = updateSetup { it.allowSpectators = value }

    override var lockedRoom: Boolean
        get() = setup.lockedRoom
        set(value) {
            updateSetup { it.lockedRoom = value }
            if (isHost && value) sendSystemMessage("Room has been locked. Players can no longer join.")
        }

    override var teamLock: Boolean
        get() = setup.teamsLocked
        set(value) = updateSetup { it.teamsLocked = value }

    override var teamMode: TeamMode? = null
    override val gameModes: GameModeSession = appKoin.get<GameModes>().newSession(this).also {
        ApiRuntime.extensionContext.own(it)
    }

    @Deprecated("Use gameModes.replace(GameSpeedMode(multiplier)).")
    override var gameSpeed: Float
        get() = (gameModes.activeModes.firstOrNull { it.id == GameSpeedMode.ID } as? GameSpeedMode)?.multiplier ?: 1f
        set(value) {
            if (value == 1f) gameModes.deactivate(GameSpeedMode.ID)
            else gameModes.replace(GameSpeedMode(value))
        }

    @Deprecated("Use gameModes.activate(MapTransformMode(id, transformer)).")
    override var gameMapTransformer: ((XMLMap) -> Unit)?
        get() = gameModes.activeModes.filterIsInstance<MapTransformMode>()
            .firstOrNull { it.id == MapTransformMode.LEGACY_ID }?.transformer
        set(value) {
            if (value == null) gameModes.deactivate(MapTransformMode.LEGACY_ID)
            else gameModes.replace(MapTransformMode(MapTransformMode.LEGACY_ID, transformer = value))
        }

    override var option: RoomOption = RoomOption()
    override val isConnecting: Boolean
        get() = network.networked && !network.isServer && network.localTeam == null

    private var generatedMapIndex = 0

    private data class GeneratedMapSelection(
        val type: GameSetupMapType,
        val name: String?,
        val path: String?,
        val nativeMapName: String?,
        val generatedPath: String,
    )

    private var generatedSelection: GeneratedMapSelection? = null
    private val generatedMaps = mutableListOf<File>()

    override fun getPlayers(): List<Team> =
        Team.getTeamsIncludingSpectators(true).filterIsInstance<Team>()

    override fun getAllPlayers(): Array<Team?> =
        Array(Team.totalTeamSlots) { Team.getTeam(it) }

    override suspend fun roomDetails(): String = network.getGameInfoText()
    override fun sendChatMessage(message: String) = network.sendChatMessage(message)
    override fun sendSystemMessage(message: String) = network.sendSystemMessage(message)
    override fun sendQuickGameCommand(command: String) = network.sendChatCommand(command)

    override fun pauseOrResumeGame(pause: Boolean) {
        if (game.state.multiplayer) {
            when {
                isHost -> network.setGamePaused(pause)
                isHostServer -> if (network.gamePaused != pause) network.sendChatCommand(if (pause) "-pause" else "-unpause")
            }
        } else {
            engine.gamePaused = pause
            engine.paused = pause
            network.gamePaused = pause
        }
    }

    override fun sendMessageToPlayer(player: Team?, title: String?, message: String, color: Int) {
        if (player != null && player != localPlayer) {
            network.getConnectionForTeam(player)?.sendPacketToClient(GamePacket.getChatPacket(title, message, color))
        } else {
            Reflect.call(
                network,
                "receiveChatMessage",
                listOf(Connection::class, Int::class, String::class, String::class),
                listOf(null, color, title, message),
            )
        }
    }

    override fun sendSurrender(player: Team) {
        if (!isHost) return
        Reflect.reifiedSet<Team>(player, "surrenderVoted", true)
        val command = engine.commandController.createCommand().apply {
            team = player
            hasSystemAction = true
            systemActionId = 100
        }
        network.queueCommand(command)
    }

    override fun spawnUnit(player: Team, unitType: UnitTypeInterface, x: Float, y: Float, size: Int) {
        if (!isHost) return
        val command = engine.commandController.createCommand().apply {
            team = player
            hasSystemAction = true
            systemActionId = 5
            setBuildWaypoint(x, y, unitType, size)
        }
        network.queueCommand(command)
    }

    override fun syncAllPlayer() {
        if (isHost) GameScope.launch { network.sendResyncSave(false, false, true) }
    }

    override fun addAI(count: Int) {
        repeat(count.coerceAtLeast(0)) {
            if (isHost) {
                if (Team.findFreeTeamId() != -1) network.addAIToGame()
            } else if (isHostServer) {
                sendQuickGameCommand("-addai")
            }
        }
        if (isHost) updateUI()
    }

    override fun applyRoomConfig(
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
    ) {
        if (isHost || isHostServer) {
            this.maxPlayerCount = maxPlayerCount
            updateSetup {
                it.sharedControl = sharedControl
                it.startingCredits = startingCredits
                it.startingUnits = startingUnits
                it.fogMode = fogMode
                it.aiDifficulty = aiDifficulty
                it.incomeMultiplier = incomeMultiplier
                it.noNukes = noNukes
                it.allowSpectators = allowSpectators
                it.teamsLocked = teamLock
            }
        }
        if (this.teamMode != teamMode) {
            this.teamMode = teamMode
            when (teamMode?.name) {
                "2t" -> network.overrideTeamLayout(TeamLayout.layout_2sides)
                "3t" -> network.overrideTeamLayout(TeamLayout.layout_3sides)
                "FFA" -> network.overrideTeamLayout(TeamLayout.layout_ffa)
                "spectators" -> network.overrideTeamLayout(TeamLayout.layout_spectators)
                null -> Unit
                else -> teamMode.onInit(this)
            }
        }
        updateUI()
    }

    override fun remainingPlayersCount(): Int = Team.countRemainingPlayers()

    override fun kickPlayer(player: Team) {
        if (isHost) network.kickTeamAndAttachedPlayer(player)
    }

    internal fun resetRuntimeState() {
        option = RoomOption()
        try {
            gameModes.clear()
        } finally {
            restoreGeneratedMapSelection()
            generatedMaps.forEach { it.delete() }
            generatedMaps.clear()
            teamMode = null
        }
    }

    /** Restore the lobby source, so a second match does not transform an already transformed map. */
    internal fun restoreGeneratedMapSelection() {
        val original = generatedSelection ?: return
        generatedSelection = null
        if (network.currentGameMapPath == original.generatedPath || setup.mapName == original.generatedPath) {
            updateSetup {
                it.mapType = original.type
                it.mapName = original.name
            }
            network.currentGameMapPath = original.path
            network.mapName = original.nativeMapName
        }
    }

    override fun disconnect(reason: String) {
        val wasConnecting = isConnecting
        network.disconnect(reason)
        if (wasConnecting) {
            game.state = GameState()
            GameEventPublisher.stateChanged(State.Menu)
        }
        engine.interfaceEngine.pauseMenuOpen = false
        GamePlatform.closeGameScreen()
    }

    override fun updateUI() {
        if (!network.isServer || GameEngine.isDedicatedServerPlatform) return
        network.updateAIDifficulty()
        network.markPlayerListDirty()
        network.sendServerInfoToAll()
        network.updateTeamStatuses()
        network.networkCallbacks?.onBattleroomUIUpdate()
    }

    override fun startGame() {
        if (!isHost) {
            if (isHostServer) network.sendChatCommand("-start")
            return
        }
        check(getPlayers().all { io.github.rwpp.api.net.packets.ModTransferHost.isReady(it) }) {
            "Cannot start while players are downloading mods"
        }
        restoreGeneratedMapSelection()
        val source = selectedMap
        var selectedPath = source.enginePath
        var generatedFile: File? = null
        try {
            if (gameModes.hasMapTransformers) {
                require(source.mapType != GameSetupMapType.savedGame) { "GameMode map transforms require a TMX map, not a saved game." }
                val map = XMLMap(source.openInputStream())
                gameModes.transform(map)
                val path =
                    "${GamePlatform.generatedMapDirectory}/generated_${generatedMapIndex++}_${System.nanoTime()}.tmx"
                generatedFile = File(path)
                val file = map.saveToFile(path)
                generatedMaps.add(file)
                generatedSelection = GeneratedMapSelection(
                    setup.mapType, setup.mapName,
                    network.currentGameMapPath, network.mapName, path
                )
                network.currentGameMapPath = path
                selectedPath = path
                updateSetup {
                    it.mapType = GameSetupMapType.customMap
                    it.mapName = path
                }
                updateUI()
            }
            // Native gameplay consumes mapName; currentGameMapPath is only the lobby cache.
            network.currentGameMapPath = selectedPath
            network.mapName = if (setup.mapType == GameSetupMapType.savedGame) null else selectedPath
            network.startGame()
        } catch (failure: Throwable) {
            restoreGeneratedMapSelection()
            generatedFile?.let { file -> file.delete(); generatedMaps.remove(file) }
            throw failure
        }
    }
}
