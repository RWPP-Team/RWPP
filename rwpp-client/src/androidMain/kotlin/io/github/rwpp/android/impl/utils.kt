/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import io.github.rwpp.api.net.Packet

fun initMap(force: Boolean = false) {
    val network = GameEngine.getInstance().networkEngine
    // gameSetup is the published room state and is available to ordinary clients too.
    // getChangeableSetup() is role-specific (null for a normal client).
    val setup = network.gameSetup
    if (network.currentGameMapPath == null || setup.mapName == null || force) {
        setup.mapType = com.corrodinggames.rts.gameFramework.network.GameSetupMapType.skirmishMap
    }
    if (network.currentGameMapPath == null || force) {
        network.currentGameMapPath = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
    }
    if (setup.mapName == null || force) setup.mapName = "[z;p10]Crossing Large (10p).tmx"
}

fun Packet.asGamePacket(): com.corrodinggames.rts.gameFramework.network.Packet {
    val byteArrayOutput = ByteArrayOutputStream()
    val gameOutput = OutputNetStream(
        DataOutputStream(byteArrayOutput)
    )
    writePacket(gameOutput)
    val bytes = byteArrayOutput.toByteArray()
    byteArrayOutput.close()
    return com.corrodinggames.rts.gameFramework.network.Packet(type!!).apply {
        data = bytes
    }
}

fun sendKickToClient(c: com.corrodinggames.rts.gameFramework.network.Connection, reason: String) {
    GameEngine.getInstance().networkEngine.sendKick(c, reason)
}

fun Resources.getResourceFileName(i: Int): String {
    val value = TypedValue()
    getValue(i, value, true)
    return value.string.toString().removePrefix("res/")
}


fun copyAssets(context: Context, assetDir: String, targetDir: String) {
    if (assetDir.isEmpty() || targetDir.isEmpty()) {
        return
    }
    val separator = File.separator

    // 获取assets目录assetDir下一级所有文件及文件夹
    val fileNames = context.resources.assets.list(assetDir)!!
    // 如果是文件夹(目录),则继续递归遍历
    if (fileNames.isNotEmpty()) {
        val targetFile = File(targetDir)
        if (!targetFile.exists() && !targetFile.mkdirs()) {
            return
        }
        for (fileName in fileNames) {
            copyAssets(context, assetDir + separator + fileName, targetDir + separator + fileName)
        }
    } else { // 文件,则执行拷贝
        val fi = File(targetDir)
        fi.parentFile!!.run { if(!exists()) mkdirs() }
        if(!fi.exists()) fi.createNewFile()
        fi.writeBytes(context.assets.open(assetDir).use { it.readBytes() })
    }
}