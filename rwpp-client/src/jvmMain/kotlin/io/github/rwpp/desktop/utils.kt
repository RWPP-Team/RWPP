/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.Packet as GamePacket
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import io.github.rwpp.api.net.Packet

fun initMap(force: Boolean = false) {
    val network = GameEngine.getInstance().networkEngine
    val setup = network.gameSetup

    if (network.currentGameMapPath == null || setup.mapName == null || force) {
        setup.mapType = GameSetupMapType.skirmishMap
    }
    if (network.currentGameMapPath == null || force) {
        network.currentGameMapPath = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
    }
    if (setup.mapName == null || force) {
        setup.mapName = "[z;p10]Crossing Large (10p).tmx"
    }
}

fun Packet.asGamePacket(): GamePacket {
    val byteArrayOutput = ByteArrayOutputStream()
    val gameOutput = OutputNetStream(DataOutputStream(byteArrayOutput))
    writePacket(gameOutput)
    val bytes = byteArrayOutput.toByteArray()
    byteArrayOutput.close()

    return GamePacket(type!!).apply {
        data = bytes
        channel = -1
    }
}