/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.net

import io.github.rwpp.api.gameVersion

data class RoomDescription(
    val uuid: String,
    val roomOwner: String = "Unnamed", // ? for official server and custom client is always 'Unnamed'
    val gameVersion: Int = io.github.rwpp.api.gameVersion,
    val netWorkAddress: String = "unknown",
    val localAddress: String = "127.0.0.1",
    val port: Long = 5123,
    val isOpen: Boolean = false,
    val creator: String = "Unnamed",
    val requiredPassword: Boolean = false,
    val mapName: String = "Unknown",
    val mapType: String = "Unknown",
    val status: String = "battleroom",
    val version: String = "Unknown",
    val isLocal: Boolean = false,
    val displayMapName: String = "Unknown", // not sure, source code doesn't use this
    val playerCurrentCount: Int? = null, // may be blank
    val playerMaxCount: Int? = null,
    val isUpperCase: Boolean = false, // ???
    val uuid2: String = "Unknown", // use to get real ip from list??
    val unknown: Boolean = false, // it is unused in source code
    val mods: String = "", // even though, this cannot be evidence that the mod has been enabled
    val roomId: Int = 0,
    val customIp: String? = null,
) {
    fun addressProvider(): String {
        if (this.roomId == 0) {
            return customIp ?: "$netWorkAddress:$port"
        }
        return "get|" + uuid2.replace("|", ".") + "|" + roomId + "|" + requiredPassword + "|" + port
    }
}

val List<RoomDescription>.sorted
    get() = this.sortedBy {
        when {
            it.isUpperCase && it.netWorkAddress.startsWith("uuid:") -> 0

            // it.isUpperCase -> 1
            it.isLocal -> 2

            it.isUpperCase && it.creator.contains("RELAY") -> 4

            it.status.contains("battleroom") -> {
                if (it.playerCurrentCount != null && it.playerMaxCount != null &&
                    it.playerCurrentCount < it.playerMaxCount &&
                    it.gameVersion == gameVersion &&
                    it.isOpen
                ) {
                    if (it.isUpperCase) 3 else 5
                } else if (it.gameVersion == gameVersion) {
                    6
                } else if (it.isUpperCase) {
                    7
                } else if (it.isOpen) {
                    9
                } else {
                    10
                }
            }

            else -> 99
        }
    }
