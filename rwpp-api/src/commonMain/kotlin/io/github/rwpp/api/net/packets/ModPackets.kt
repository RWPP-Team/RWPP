/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.packets

import io.github.rwpp.api.net.PacketDirection
import io.github.rwpp.api.net.PacketKey
import io.github.rwpp.api.net.nativePacketCodec

/** Shared native-stream codecs at the existing mod-transfer wire IDs. */
object ModPackets {
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
