/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.packets

import io.github.rwpp.api.net.PacketDirection
import io.github.rwpp.api.net.PacketKey
import io.github.rwpp.api.net.nativePacketCodec

/**
 * Existing RWPP wire IDs; names and codecs are local registrations, no new wire protocol.
 *
 * The server-info packets of the multiplayer server list are not listed here: they belong to the
 * shared API, in `io.github.rwpp.api.net.packets.ServerInfoPackets`.
 */
object ProductPackets {
    val MOD_REQUEST = PacketKey(
        "rwpp.mod_request", ModPacket.MOD_DOWNLOAD_REQUEST,
        PacketDirection.CLIENT_TO_SERVER, nativePacketCodec { ModPacket.RequestPacket() }, 64 * 1024
    )
    val MOD_DATA = PacketKey(
        "rwpp.mod_data", ModPacket.DOWNLOAD_MOD_PACK,
        PacketDirection.SERVER_TO_CLIENT, nativePacketCodec { ModPacket.ModPackPacket() }, PacketKey.MAX_PAYLOAD_BYTES
    )
    val MOD_READY = PacketKey(
        "rwpp.mod_ready", ModPacket.MOD_RELOAD_FINISH,
        PacketDirection.CLIENT_TO_SERVER, nativePacketCodec { ModPacket.ModReloadFinishPacket() }, 16
    )
}
