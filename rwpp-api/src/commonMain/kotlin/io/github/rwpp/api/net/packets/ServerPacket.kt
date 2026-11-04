/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.packets

import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.api.net.Packet
import io.github.rwpp.api.net.ServerStatus

/** The server-info exchange used by the multiplayer server list. */
sealed class ServerPacket : Packet() {
    /** Asks a server for its description. Only [io.github.rwpp.api.config.ServerType.Server] entries answer. */
    class ServerInfoGetPacket : ServerPacket() {
        override val type: Int = InternalPacketType.PRE_GET_SERVER_INFO_FROM_LIST.type

        override fun readPacket(input: InputNetStream) {
            require(input.readInt() == 1) { "Unsupported server-info query marker" }
        }

        override fun writePacket(output: OutputNetStream) {
            output.writeInt(1) // buf must > 6
        }
    }

    /** The host's answer to [ServerInfoGetPacket]. */
    class ServerInfoReceivePacket(
        var name: String = "",
        var currentPlayer: Int = 0,
        var maxPlayerSize: Int = 0,
        var mapName: String = "",
        var description: String = "",
        var version: String = "",
        var mods: String = "",
        var status: ServerStatus = ServerStatus.BattleRoom,
        var iconBytes: ByteArray = byteArrayOf(),
    ) : ServerPacket() {
        override val type: Int = InternalPacketType.RECEIVE_SERVER_INFO_FROM_LIST.type
        var ping: Int = 0
        override fun readPacket(input: InputNetStream) {
            name = input.readUTF()
            currentPlayer = input.readInt()
            maxPlayerSize = input.readInt()
            mapName = input.readUTF()
            description = input.readUTF()
            version = input.readUTF()
            mods = input.readUTF()
            iconBytes = input.readByteArray()
            val statusOrdinal = input.readInt()
            status = ServerStatus.entries.getOrNull(statusOrdinal)
                ?: throw IllegalArgumentException("Unknown server status: $statusOrdinal")
            require(currentPlayer >= 0 && maxPlayerSize >= 0) { "Invalid server player counts" }
        }

        override fun writePacket(output: OutputNetStream) {
            output.writeUTF(name)
            output.writeInt(currentPlayer)
            output.writeInt(maxPlayerSize)
            output.writeUTF(mapName)
            output.writeUTF(description)
            output.writeUTF(version)
            output.writeUTF(mods)
            output.writeBytesWithLength(iconBytes)
            output.writeInt(status.ordinal)
        }
    }
}
