/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.api.Initialization
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.net.Net
import io.github.rwpp.net.RTSBoxDownloadWeeklyProtocol
import io.github.rwpp.net.RTSBoxProtocol
import io.github.rwpp.net.bbsProtocols
import org.koin.core.annotation.Single

/** Registers RWPP-only network behavior on top of the shared API network service. */
@Single(binds = [Initialization::class])
class NetProductInitialization(
    private val net: Net,
    private val multiplayerPreferences: MultiplayerPreferences,
) : Initialization {
    override fun init() {
        net.bbsProtocols.apply {
            if (none { it.name == RTSBoxProtocol.name }) add(RTSBoxProtocol)
            if (none { it.name == RTSBoxDownloadWeeklyProtocol.name }) add(RTSBoxDownloadWeeklyProtocol)
        }

        val serverConfigs = multiplayerPreferences.allServerConfig
        if (serverConfigs.none { it.name == OFFICIAL_ROOM_LIST_NAME }) {
            serverConfigs.add(officialRoomList)
        }
        if (serverConfigs.none { it.name == Q_ROOM_LIST_NAME }) {
            serverConfigs.add(qRoomList)
        }
    }

    private companion object {
        const val OFFICIAL_ROOM_LIST_NAME = "Official Room List"
        const val Q_ROOM_LIST_NAME = "Q Room List"
    }
}

private val officialRoomList: ServerConfig
    get() = ServerConfig(
        "http://gs1.corrodinggames.com/masterserver/1.4/interface?action=list&game_version=176&game_version_beta=false;http://gs4.corrodinggames.net/masterserver/1.4/interface?action=list&game_version=176&game_version_beta=false",
        "Official Room List",
        ServerType.RoomList,
        editable = false,
        customRoomHostProtocol = "RCN",
    )

private val qRoomList: ServerConfig
    get() = ServerConfig(
        "",
        "Q Room List",
        ServerType.RoomList,
        editable = false,
        customRoomListProvider = "qRoomListProvider",
        customRoomHostProtocol = "QN",
    )
