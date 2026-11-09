/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.event.events.*
import io.github.rwpp.api.game.impl.NativeGameLifecycle
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.Socket

private data class MapLoadContext(
    val engine: GameEngine,
    val path: String?,
    val type: GameEngineMapType,
    val loadAi: Boolean,
    val keepTeams: Boolean,
)

private data class ReplayLoadContext(
    val engine: ReplayEngine,
    val name: String,
)

internal object NativeNetworkLifecycleCallbacks {
    fun isClientMapLoadPending(): Boolean {
        val network = GameEngine.getInstance().networkEngine
        return io.github.rwpp.api.game.NetworkGameLoadingState.isLoadingMap &&
                network.networked && !network.isServer
    }

    /**
     * NetworkEngine.update runs every frame on both platforms, so it also drives the ready
     * handshake. A frame hook that returns early must never be the only trigger.
     */
    fun acknowledgeClientReady(target: Any) {
        io.github.rwpp.api.game.impl.ClientTimelineGuard.poll(target as NetworkEngine)
        NativeGameLifecycle.acknowledgeClientReadyAfterMapLoad(GameEngine.getInstance())
    }

    fun publishMultiplayerHostStarted(
        target: Any,
        started: Boolean,
    ) {
        val network = target as NetworkEngine
        if (started && network.networked && network.isServer && !network.singlePlayerServer) {
            NativeGameLifecycle.onMultiplayerHostStarted(network)
        }
    }

    fun publishSinglePlayerHostStarted(started: Boolean, target: Any) {
        val network = target as NetworkEngine
        if (started && network.networked && network.isServer && network.singlePlayerServer) {
            NativeGameLifecycle.onSinglePlayerHostStarted(network)
        }
    }

    fun syncSandboxMode(started: Boolean, target: Any) {
        if (started) {
            NativeGameLifecycle.onSandboxModeStarted(target as NetworkEngine)
        }
    }

    fun publishJoinAttempt(address: String) {
        NativeGameLifecycle.onJoinAttempt(address)
    }

    fun syncClientConnected(started: Boolean, target: Any, socket: Socket) {
        val network = target as NetworkEngine
        if (started && network.networked && !network.isServer) {
            val address = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" }
            NativeGameLifecycle.onClientConnected(network, address, socket)
        }
    }

    fun syncNetworkDisconnected(
        target: Any,
        reason: String,
    ) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onNetworkDisconnected(network, reason)
    }

    fun resetRoomMap(target: Any, keepTeams: Boolean) {
        NativeGameLifecycle.onNetworkStateReset(target as NetworkEngine, keepTeams)
    }

    fun syncReturnToBattleroom(target: Any) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onReturnToBattleroom(network)
        if (hasNativeEventListeners(RoomReturnedEvent::class)) {
            broadcastNativeEvent(RoomReturnedEvent(network))
        }
    }

    fun clearFailedNetworkStart(@Suppress("UNUSED_PARAMETER") target: Any) {
        NativeGameLifecycle.onNativeNetworkStartFailed()
    }

    fun publishMapSelectionChanged(
        target: Any,
        setup: Any,
    ) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onSetupApplied(network)
        if (network.isServer) {
            if (hasNativeEventListeners(RoomSetupAppliedEvent::class)) {
                broadcastNativeEvent(
                    RoomSetupAppliedEvent(
                        network,
                        setup as GameSetup,
                    ),
                )
            }
        }
    }

    fun onNetworkPacketStarting(target: Any, packet: Packet) {
        if (io.github.rwpp.api.game.impl.ClientPacketSessionGuard.isStale(target as NetworkEngine, packet)) return
        if (packet.type == 178) NativeGameLifecycle.onNativeRedirect(packet.connection)
        if (packet.type == 120) NativeGameLifecycle.onClientNetworkGameStarting(target as NetworkEngine)
    }

    fun confirmClientRoom(target: Any, packet: Packet) {
        val network = target as NetworkEngine
        if (io.github.rwpp.api.game.impl.ClientPacketSessionGuard.isStale(network, packet)) return
        if (packet.type == 106) NativeGameLifecycle.onServerInfoReceived(network)
        val clientRoster = packet.type == 115 && !network.isServer
        val relayHost = packet.type == 170 && network.isServer && network.usingRelayServer
        if ((clientRoster || relayHost) && network.networked && network.localTeam != null) {
            NativeGameLifecycle.onClientRoomJoined(network, packet.connection)
        }
    }
}

internal object NativeEngineLifecycleCallbacks {
    fun acknowledgeClientReady(target: Any) {
        NativeGameLifecycle.acknowledgeClientReadyAfterMapLoad(target as GameEngine)
    }

    private fun load(engine: GameEngine, loadAi: Boolean, keepTeams: Boolean, mapType: GameEngineMapType) =
        MapLoadContext(engine, engine.currentMapPath, mapType, loadAi, keepTeams)

    fun wrapMapLoad(
        loadAi: Boolean,
        keepTeams: Boolean,
        mapType: GameEngineMapType,
        operation: () -> Unit,
    ) {
        val engine = GameEngine.getInstance()
        val context = load(engine, loadAi, keepTeams, mapType)
        if (hasNativeEventListeners(MapLoadEvent::class)) {
            broadcastNativeEvent(context.toEvent(MapLoadPhase.BEFORE))
        }
        try {
            operation()
        } catch (failure: Throwable) {
            val nativeFailure = unwrapInvocationTarget(failure)
            NativeGameLifecycle.onMapLoadFailed()
            notifyMapFailure(context, nativeFailure)
            throw nativeFailure
        }

        if (engine.map?.mapLoaded == true && engine.gameJustLoaded) {
            if (hasNativeEventListeners(MapLoadEvent::class)) {
                broadcastNativeEvent(context.toEvent(MapLoadPhase.LOADED))
            }
            NativeGameLifecycle.onMapLoaded()
        } else {
            NativeGameLifecycle.onMapLoadFailed()
            notifyMapFailure(context, IllegalStateException("Native map loading returned without a loaded map"))
        }
    }

    private fun notifyMapFailure(context: MapLoadContext, failure: Throwable) {
        if (!hasNativeEventListeners(MapLoadEvent::class)) return
        try {
            broadcastNativeEvent(context.toEvent(MapLoadPhase.FAILED, failure))
        } catch (notificationFailure: Throwable) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun MapLoadContext.toEvent(phase: MapLoadPhase, error: Throwable? = null) =
        MapLoadEvent(engine, path, type, loadAi, keepTeams, phase, error)

    private fun unwrapInvocationTarget(failure: Throwable): Throwable {
        var cause = failure
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return cause
    }

    fun syncNativeMenuState(
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") activity: Any?,
        @Suppress("UNUSED_PARAMETER") gameView: Any?,
        resumeWithMenu: Boolean,
    ) {
        if (resumeWithMenu) {
            NativeGameLifecycle.onMenuShown()
        } else {
            GameEventPublisher.resumeGame()
        }
    }
}

internal object NativeReplayLifecycleCallbacks {
    fun wrapReplayLoad(
        replayName: String,
        @Suppress("UNUSED_PARAMETER") file: File,
        operation: (String, File) -> Boolean,
    ): Boolean {
        val replay = GameEngine.getInstance().replayEngine
        NativeGameLifecycle.onReplayLoading()
        val context = ReplayLoadContext(replay, replayName)
        if (hasNativeEventListeners(ReplayLoadEvent::class)) {
            broadcastNativeEvent(context.toEvent(ReplayLoadPhase.BEFORE))
        }
        val nativeResult = try {
            operation(replayName, file)
        } catch (failure: Throwable) {
            val nativeFailure = unwrapInvocationTarget(failure)
            notifyReplayFailure(context, nativeFailure)
            throw nativeFailure
        }

        val success = NativeGameLifecycle.onReplayLoaded(nativeResult && replay.isPlayingReplay())
        if (hasNativeEventListeners(ReplayLoadEvent::class)) {
            val phase = if (success) ReplayLoadPhase.LOADED else ReplayLoadPhase.FAILED
            broadcastNativeEvent(context.toEvent(phase))
        }
        return nativeResult
    }

    private fun notifyReplayFailure(context: ReplayLoadContext, failure: Throwable) {
        try {
            NativeGameLifecycle.onReplayLoaded(false)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        try {
            if (hasNativeEventListeners(ReplayLoadEvent::class)) {
                broadcastNativeEvent(context.toEvent(ReplayLoadPhase.FAILED, failure))
            }
        } catch (notificationFailure: Throwable) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun ReplayLoadContext.toEvent(phase: ReplayLoadPhase, error: Throwable? = null) =
        ReplayLoadEvent(engine, name, phase, error)

    private fun unwrapInvocationTarget(failure: Throwable): Throwable {
        var cause = failure
        while (cause is InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return cause
    }
}
