/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.server

import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.net.packets.ServerPacket
import io.github.rwpp.api.server.ServerData

/**
 * The headless server's [ServerData].
 *
 * The client keeps the same fields in Compose state so its server list redraws; a dedicated server
 * has no UI, so plain volatile fields are enough and read consistently from the console thread.
 */
class HeadlessServerData(override val config: ServerConfig) : ServerData {
    @Volatile
    override var isLoading: Boolean = false

    @Volatile
    override var infoPacket: ServerPacket.ServerInfoReceivePacket? = null
}
