/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet
import io.github.rwpp.api.game.impl.ClientPacketSessionGuard
import io.github.rwpp.api.game.impl.ClientTimelineGuard
import io.github.rwpp.api.game.impl.NativeGameFaults
import kim.der.asm.api.annotation.*
import java.net.Socket

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object ClientSessionSafetyMixin {
    @AsmInject(method = "startClientConnection(Ljava/net/Socket;)Z", target = InjectionPoint.HEAD, require = 1)
    fun begin(self: Any, socket: Socket) = ClientPacketSessionGuard.begin(self as NetworkEngine, socket)

    @ModifyReturnValue(method = "isPacketFiltered(Lcom/corrodinggames/rts/gameFramework/network/Packet;)Z", require = 1)
    fun stale(result: Boolean, packet: Packet): Boolean = result ||
            ClientPacketSessionGuard.isStale(GameEngine.getInstance().networkEngine, packet)

    @AsmInject(method = "checkCanRunNextGameStep(FZ)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun step(callback: CallbackInfo, self: Any, delta: Float, checkingNetwork: Boolean) {
        if (ClientTimelineGuard.beforeStep(self as NetworkEngine)) callback.cancel()
    }

    @ModifyReturnValue(method = "shouldGameBePaused()Z", require = 1)
    fun pause(result: Boolean): Boolean = result || ClientTimelineGuard.isPaused(GameEngine.getInstance())

    @WrapMethod(method = "processGamePacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V", require = 1)
    fun gamePacket(packet: Packet, original: Operation<Unit>) {
        val engine = GameEngine.getInstance()
        if (ClientPacketSessionGuard.isStale(engine.networkEngine, packet)) return
        NativeGameFaults.runPacket(engine, "game packet ${packet.type}") { original.call(packet) }
    }

    @WrapMethod(method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V", require = 1)
    fun systemPacket(packet: Packet, original: Operation<Unit>) {
        val engine = GameEngine.getInstance()
        if (ClientPacketSessionGuard.isStale(engine.networkEngine, packet)) return
        NativeGameFaults.runPacket(engine, "system packet ${packet.type}") { original.call(packet) }
    }
}
