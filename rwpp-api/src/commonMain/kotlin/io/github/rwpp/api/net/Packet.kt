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

/** Extension packet using the native game stream format. */
abstract class Packet {
    abstract val type: Int

    abstract fun readPacket(input: InputNetStream)

    abstract fun writePacket(output: OutputNetStream)

    fun toBytes(): ByteArray {
        val gameOutput = OutputNetStream()
        writePacket(gameOutput)
        return gameOutput.toByteArray()
    }

    /**
     * 转成游戏原生的包，交给游戏的网络引擎发送。
     */
    fun toGamePacket(): GamePacket = GamePacket(type).apply { data = toBytes() }

    companion object {
        /** 读取游戏原生包的类型。 */
        @JvmStatic
        fun typeOf(gamePacket: GamePacket): Int = gamePacket.type
    }
}
