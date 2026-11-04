/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.core

import io.github.rwpp.api.game.Game
import io.github.rwpp.api.server.ServerInfoProvider
import io.github.rwpp.api.server.serverInfoOfRoom
import io.github.rwpp.appKoin

/**
 * The client's answer to a server-info query: the room this client hosts, described with its enabled
 * mod list. Clients that are not hosting return null, so they stay silent on the server list.
 */
object RwppClientServerInfo : ServerInfoProvider {
    override fun describe() = serverInfoOfRoom(appKoin.get<Game>(), VERSION_LABEL)

    private const val VERSION_LABEL = "v1.15 - RWPP Client"
}
