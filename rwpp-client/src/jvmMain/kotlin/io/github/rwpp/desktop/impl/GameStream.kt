/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

class GameStream(
    private val stream: com.corrodinggames.rts.gameFramework.network.InputNetStream
) {
    fun readByte() = stream.readByte()
    fun readBool() = stream.readBoolean()
    fun readInt() = stream.readInt()
    fun readFloat() = stream.readFloat()
    fun readDouble() = stream.readDouble()
    fun readLong() = stream.readLong()
    fun readUTF() = stream.readUTF()
}