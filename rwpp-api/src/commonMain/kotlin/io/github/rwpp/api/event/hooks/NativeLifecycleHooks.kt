/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.event.GameEventPublisher
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.Packet
import io.github.rwpp.api.game.impl.NativeGameLifecycle
import io.github.rwpp.api.event.events.MapLoadEvent
import io.github.rwpp.api.event.events.MapLoadPhase
import io.github.rwpp.api.event.events.ReplayLoadEvent
import io.github.rwpp.api.event.events.ReplayLoadPhase
import io.github.rwpp.api.event.events.RoomReturnedEvent
import io.github.rwpp.api.event.events.RoomSetupAppliedEvent
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
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

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeNetworkLifecycleMixin {
    @AsmInject(method = "startServer(Z)Z", target = InjectionPoint.TAIL, require = 1)
    fun publishMultiplayerHostStarted(
        callback: CallbackInfo,
        target: Any,
        @Suppress("UNUSED_PARAMETER") createAdminPlayer: Boolean,
    ) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && network.isServer && !network.singlePlayerServer) {
            NativeGameLifecycle.onMultiplayerHostStarted(network)
        }
    }

    @AsmInject(method = "startSingleplayerServer()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishSinglePlayerHostStarted(callback: CallbackInfo, target: Any) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && network.isServer && network.singlePlayerServer) {
            NativeGameLifecycle.onSinglePlayerHostStarted(network)
        }
    }

    @AsmInject(method = "startSandboxMode()Z", target = InjectionPoint.TAIL, require = 1)
    fun syncSandboxMode(callback: CallbackInfo, target: Any) {
        if (callback.getReturnValue<Boolean>() == true) {
            NativeGameLifecycle.onSandboxModeStarted(target as NetworkEngine)
        }
    }

    @AsmInject(
        method = "connectToServerAsync(Ljava/lang/String;ZLjava/lang/Runnable;)Lcom/corrodinggames/rts/gameFramework/network/ConnectSocketToServerRunnable;",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun publishJoinAttempt(
        @Suppress("UNUSED_PARAMETER") target: Any,
        address: String,
        @Suppress("UNUSED_PARAMETER") forceTcp: Boolean,
        @Suppress("UNUSED_PARAMETER") callback: Runnable,
    ) {
        NativeGameLifecycle.onJoinAttempt(address)
    }

    @AsmInject(
        method = "startClientConnection(Ljava/net/Socket;)Z",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun syncClientConnected(callback: CallbackInfo, target: Any, socket: Socket) {
        val network = target as NetworkEngine
        if (callback.getReturnValue<Boolean>() == true && network.networked && !network.isServer) {
            val address = socket.inetAddress?.hostAddress?.let { "$it:${socket.port}" }
            NativeGameLifecycle.onClientConnected(network, address)
        }
    }

    @AsmInject(method = "disconnect(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun syncNetworkDisconnected(
        target: Any,
        reason: String,
    ) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onNetworkDisconnected(network, reason)
    }

    @AsmInject(method = "returnToBattleroom()V", target = InjectionPoint.TAIL, require = 1)
    fun syncReturnToBattleroom(target: Any) {
        val network = target as NetworkEngine
        NativeGameLifecycle.onReturnToBattleroom(network)
        if (hasNativeEventListeners(RoomReturnedEvent::class)) {
            broadcastNativeEvent(RoomReturnedEvent(network))
        }
    }

    @AsmInject(
        method = "applyChangedSetup(Lcom/corrodinggames/rts/gameFramework/network/GameSetup;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
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

    @AsmInject(method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V", target = InjectionPoint.TAIL, require = 1)
    fun confirmClientRoom(target: Any, packet: Packet) {
        val network = target as NetworkEngine
        if (packet.type == 115 && !network.isServer && network.networked && network.localTeam != null) {
            NativeGameLifecycle.onClientRoomJoined(network, packet.connection)
        }
    }
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeEngineLifecycleMixin {
    private fun load(engine: GameEngine, loadAi: Boolean, keepTeams: Boolean, mapType: GameEngineMapType) =
        MapLoadContext(engine, engine.currentMapPath, mapType, loadAi, keepTeams)

    @WrapMethod(method = "loadMap(ZZLcom/corrodinggames/rts/gameFramework/GameEngineMapType;)V", require = 1)
    fun wrapMapLoad(
        loadAi: Boolean,
        keepTeams: Boolean,
        mapType: GameEngineMapType,
        operation: Operation<Unit>,
    ) {
        val engine = GameEngine.getInstance()
        val context = load(engine, loadAi, keepTeams, mapType)
        if (hasNativeEventListeners(MapLoadEvent::class)) {
            broadcastNativeEvent(context.toEvent(MapLoadPhase.BEFORE))
        }
        try {
            operation.call(loadAi, keepTeams, mapType)
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

    @AsmInject(
        method = "setActivityAndGameView(Landroid/app/Activity;Lcom/corrodinggames/rts/appFramework/GameViewInterface;Z)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
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

@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
object NativeReplayLifecycleMixin {
    @WrapMethod(method = "loadReplayFile(Ljava/lang/String;Ljava/io/File;)Z", require = 1)
    fun wrapReplayLoad(
        replayName: String,
        @Suppress("UNUSED_PARAMETER") file: File,
        operation: Operation<Boolean>,
    ): Boolean {
        val replay = GameEngine.getInstance().replayEngine
        NativeGameLifecycle.onReplayLoading()
        val context = ReplayLoadContext(replay, replayName)
        if (hasNativeEventListeners(ReplayLoadEvent::class)) {
            broadcastNativeEvent(context.toEvent(ReplayLoadPhase.BEFORE))
        }
        val nativeResult = try {
            operation.call(replayName, file)
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
