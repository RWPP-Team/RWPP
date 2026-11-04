/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import io.github.rwpp.api.net.impl.NativeTcpCallbacks
import kim.der.asm.api.annotation.*
import java.net.ServerSocket
import java.net.Socket

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine", priority = 900)
object NativeAddressConnectMixin {
    @JvmStatic
    @WrapMethod(method = "connectToServer(Ljava/lang/String;Z)Ljava/net/Socket;", require = 1)
    fun connect(address: String, forceTcp: Boolean, original: Operation<Socket>): Socket =
        NativeTcpCallbacks.connect(address, forceTcp) { nativeAddress, nativeForceTcp ->
            original.call(nativeAddress, nativeForceTcp)
        }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine", priority = 1100)
object NativeTcpConnectMixin {
    @JvmStatic
    @Redirect(
        method = "connectToServer(Ljava/lang/String;Z)Ljava/net/Socket;",
        at = At(value = InjectionPoint.INVOKE, target = "java.net.Socket.<init>()V"),
        require = 1,
    )
    fun createSocket(): Socket = NativeTcpCallbacks.createSocket()
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NewConnectionRunnable")
object NativeTcpListenMixin {
    @AsmInject(method = "openServerSocket(Z)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun openUdp(callback: CallbackInfo, target: Any, udp: Boolean) {
        if (NativeTcpCallbacks.openUdp(target, udp)) callback.cancel()
    }

    @JvmStatic
    @Redirect(
        method = "openServerSocket(Z)V",
        at = At(value = InjectionPoint.INVOKE, target = "java.net.ServerSocket.<init>(I)V"),
        require = 1,
    )
    fun createServerSocket(port: Int): ServerSocket = NativeTcpCallbacks.createServerSocket(port)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/Connection")
object NativeTcpConnectionMixin {
    @AsmInject(method = "startWorkers()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun start(callback: CallbackInfo, target: Any) {
        if (NativeTcpCallbacks.start(target)) callback.cancel()
    }

    @AsmInject(
        method = "sendPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun send(callback: CallbackInfo, target: Any, packet: Any) {
        if (NativeTcpCallbacks.send(target, packet)) callback.cancel()
    }

    @AsmInject(method = "stopSendWorkerAndRemove()V", target = InjectionPoint.TAIL, require = 1)
    fun stop(target: Any) = NativeTcpCallbacks.stop(target)
}
