/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.impl

import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.PacketDirection
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Common receive hook for registered native extension system packets.
 *
 * Typed packet IDs 500..999 and the API-owned namespaced envelope at 1000 are processed
 * synchronously by the game's receive worker. Receivers must not mutate simulation state directly;
 * use the game-thread dispatcher when needed.
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkPacketMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun dispatchExtensionPacket(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        nativePacket: Any,
    ) {
        val network = target as NetworkEngine
        val packet = nativePacket as GamePacket
        val incomingDirection = if (network.isServer) {
            PacketDirection.CLIENT_TO_SERVER
        } else {
            PacketDirection.SERVER_TO_CLIENT
        }
        val consumed = appKoin.get<Net>().dispatchNativePacket(
            packetType = packet.type,
            payload = packet.data,
            connection = packet.connection,
            incomingDirection = incomingDirection,
        )
        if (consumed) callbackInfo.cancel()
    }
}
