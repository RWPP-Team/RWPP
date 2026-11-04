/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server

import io.github.rwpp.api.appKoin
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.server.ServerInfoProvider
import io.github.rwpp.api.server.serverInfoOfRoom

/**
 * The headless server's answer to the server-info query of the RWPP server list.
 *
 * The room state comes from the shared game service, so a dedicated server appears with the same
 * name, player counts, map, and mod list that a hosting client reports.
 */
object NativeServerInfo : ServerInfoProvider {
    override fun describe() = serverInfoOfRoom(appKoin.get<Game>(), VERSION_LABEL)

    private const val VERSION_LABEL = "v1.15 - RWPP Server"
}
