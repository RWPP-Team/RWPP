/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import com.corrodinggames.rts.gameFramework.network.Packet
import io.github.rwpp.api.event.hooks.NativeNetworkLifecycleCallbacks
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeNetworkLifecycleMixin {
    @AsmInject(method = "startServer(Z)Z", target = InjectionPoint.TAIL, require = 1)
    fun publishMultiplayerHostStarted(callback: CallbackInfo, target: Any, createAdminPlayer: Boolean) =
        NativeNetworkLifecycleCallbacks.publishMultiplayerHostStarted(
            target,
            callback.getReturnValue<Boolean>() == true,
        )

    @AsmInject(method = "startSingleplayerServer()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishSinglePlayerHostStarted(callback: CallbackInfo, target: Any) =
        NativeNetworkLifecycleCallbacks.publishSinglePlayerHostStarted(
            callback.getReturnValue<Boolean>() == true,
            target,
        )

    @AsmInject(method = "startSandboxMode()Z", target = InjectionPoint.TAIL, require = 1)
    fun syncSandboxMode(callback: CallbackInfo, target: Any) =
        NativeNetworkLifecycleCallbacks.syncSandboxMode(callback.getReturnValue<Boolean>() == true, target)

    @AsmInject(
        method = "connectToServerAsync(Ljava/lang/String;ZLjava/lang/Runnable;)Lcom/corrodinggames/rts/gameFramework/network/ConnectSocketToServerRunnable;",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun publishJoinAttempt(target: Any, address: String, forceTcp: Boolean, callback: Runnable) =
        NativeNetworkLifecycleCallbacks.publishJoinAttempt(address)

    @AsmInject(method = "startClientConnection(Ljava/net/Socket;)Z", target = InjectionPoint.TAIL, require = 1)
    fun syncClientConnected(callback: CallbackInfo, target: Any, socket: java.net.Socket) =
        NativeNetworkLifecycleCallbacks.syncClientConnected(
            callback.getReturnValue<Boolean>() == true,
            target,
            socket,
        )

    @AsmInject(method = "disconnect(Ljava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun syncNetworkDisconnected(target: Any, reason: String) =
        NativeNetworkLifecycleCallbacks.syncNetworkDisconnected(target, reason)

    @AsmInject(method = "resetNetworkState(Z)V", target = InjectionPoint.TAIL, require = 1)
    fun resetRoomMap(target: Any, keepTeams: Boolean) =
        NativeNetworkLifecycleCallbacks.resetRoomMap(target, keepTeams)

    @AsmInject(method = "returnToBattleroom()V", target = InjectionPoint.TAIL, require = 1)
    fun syncReturnToBattleroom(target: Any) = NativeNetworkLifecycleCallbacks.syncReturnToBattleroom(target)

    @AsmInject(method = "onStartGameFailed()V", target = InjectionPoint.TAIL, require = 1)
    fun clearFailedNetworkStart(target: Any) = NativeNetworkLifecycleCallbacks.clearFailedNetworkStart(target)

    @AsmInject(
        method = "applyChangedSetup(Lcom/corrodinggames/rts/gameFramework/network/GameSetup;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishMapSelectionChanged(target: Any, setup: Any) =
        NativeNetworkLifecycleCallbacks.publishMapSelectionChanged(target, setup)

    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun beginNetworkPacket(target: Any, packet: Packet) =
        NativeNetworkLifecycleCallbacks.onNetworkPacketStarting(target, packet)

    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun confirmClientRoom(target: Any, packet: Packet) =
        NativeNetworkLifecycleCallbacks.confirmClientRoom(target, packet)

    /** Packet 120 arrives on the receive worker before the native map-load callback runs. */
    @kim.der.asm.api.annotation.ModifyReturnValue(method = "shouldGameBePaused()Z", require = 1)
    fun awaitClientMapLoad(result: Boolean): Boolean =
        result || NativeNetworkLifecycleCallbacks.isClientMapLoadPending()

    /**
     * Frame hooks on the game engine can be skipped while the native view reports no update.
     * The network update always runs for a live client, so the ready ack is retried here too.
     */
    @AsmInject(method = "update(F)V", target = InjectionPoint.TAIL, require = 1)
    fun acknowledgeClientReady(target: Any, delta: Float) =
        NativeNetworkLifecycleCallbacks.acknowledgeClientReady(target)

}
