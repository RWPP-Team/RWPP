/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.server

import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.net.packets.ServerPacket

/**
 * One entry of the multiplayer server list: its configuration plus the last answer it gave.
 *
 * The API only declares the contract, because the observation mechanism belongs to the platform.
 * The client implements [isLoading] and [infoPacket] with Compose state so the server list redraws
 * itself, while the headless server implements them with plain volatile fields.
 */
interface ServerData {
    val config: ServerConfig
    var isLoading: Boolean
    var infoPacket: ServerPacket.ServerInfoReceivePacket?
}
