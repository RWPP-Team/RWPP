/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net.packets

import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.api.net.InternalPacketType
import io.github.rwpp.api.net.Packet

@Suppress("MemberVisibilityCanBePrivate")
object GamePacket {
    fun getPacket(packetType: Int, action: (OutputNetStream) -> Unit): Packet = object : Packet() {
        override val type: Int = packetType

        override fun readPacket(input: InputNetStream) {}

        override fun writePacket(output: OutputNetStream) {
            action(output)
        }
    }

    fun getChatPacket(title: String?, message: String, color: Int): Packet =
        getPacket(InternalPacketType.CHAT.type) { output ->
            output.writeUTF(message)
            output.writeByte(3)
            output.writeNullableUTF(title)
            output.writeInt(0)
            output.writeInt(color)
        }
}
