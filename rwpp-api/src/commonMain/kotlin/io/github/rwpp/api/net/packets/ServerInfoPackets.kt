/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.packets

import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.api.net.PacketDirection
import io.github.rwpp.api.net.PacketKey
import io.github.rwpp.api.net.nativePacketCodec

/**
 * The two extension packets of the server-info exchange, kept at their existing wire IDs.
 *
 * Both sides register the same keys, so a client can query any host that implements the protocol and
 * a host can answer any client that asks.
 */
object ServerInfoPackets {
    val QUERY = PacketKey(
        "rwpp.server_query",
        InternalPacketType.PRE_GET_SERVER_INFO_FROM_LIST.type,
        PacketDirection.CLIENT_TO_SERVER,
        nativePacketCodec { ServerPacket.ServerInfoGetPacket() },
        16,
    )

    val INFO = PacketKey(
        "rwpp.server_info",
        InternalPacketType.RECEIVE_SERVER_INFO_FROM_LIST.type,
        PacketDirection.SERVER_TO_CLIENT,
        nativePacketCodec { ServerPacket.ServerInfoReceivePacket() },
        64 * 1024,
    )
}
