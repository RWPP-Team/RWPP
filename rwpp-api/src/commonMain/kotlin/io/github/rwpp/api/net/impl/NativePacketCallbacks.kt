/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.impl

import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.net.Net
import io.github.rwpp.api.net.PacketDirection
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket

/** Dispatches a native system packet to the registered extension handlers. */
internal fun dispatchNativeExtensionPacket(target: Any, nativePacket: Any): Boolean {
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
    return consumed
}
