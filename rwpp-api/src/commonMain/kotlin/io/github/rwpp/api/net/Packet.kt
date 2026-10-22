/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream

/**
 * 扩展之间交换的网络包。
 *
 * 包体自身沿用游戏的数据流，因此可以直接转成游戏原生的 [GamePacket] 交给游戏的网络引擎收发；
 * 需要跨端或跨版本传输时，再覆写 [writeToProto] 与 [readFromProto]，用 protobuf 通道读写，
 * 不必手工解析字节。
 */
abstract class Packet {
    abstract val type: Int

    abstract fun readPacket(input: InputNetStream)

    abstract fun writePacket(output: OutputNetStream)

    /**
     * protobuf 通道的写入实现。
     *
     * 字段号 1 保留给 [type]，实现应从字段号 2 开始写。
     */
    open fun writeToProto(output: CodedOutputStream) = Unit

    /**
     * protobuf 通道的读取实现，与 [writeToProto] 成对出现。
     */
    open fun readFromProto(input: CodedInputStream) = Unit

    fun toBytes(): ByteArray {
        val gameOutput = OutputNetStream()
        writePacket(gameOutput)
        return gameOutput.toByteArray()
    }

    fun toProtoBytes(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val output = CodedOutputStream.newInstance(buffer)
        output.writeInt32(PROTO_FIELD_TYPE, type)
        writeToProto(output)
        output.flush()
        return buffer.toByteArray()
    }

    fun readProtoBytes(bytes: ByteArray) {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == 0) break
            if (tag == PROTO_TAG_TYPE) {
                input.readInt32()
            } else {
                readFromProto(input)
            }
        }
    }

    /**
     * 转成游戏原生的包，交给游戏的网络引擎发送。
     */
    fun toGamePacket(): GamePacket = GamePacket(type).apply { data = toBytes() }

    companion object {
        private const val PROTO_FIELD_TYPE = 1
        private const val PROTO_TAG_TYPE = (PROTO_FIELD_TYPE shl 3) or 0

        /** 读取游戏原生包的类型。 */
        @JvmStatic
        fun typeOf(gamePacket: GamePacket): Int = gamePacket.type
    }
}
