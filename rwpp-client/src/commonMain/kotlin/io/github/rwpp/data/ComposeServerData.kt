/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.net.packets.ServerPacket
import io.github.rwpp.api.server.ServerData

/**
 * The client's [ServerData]: both fields are Compose state, so reading them inside a composable makes
 * the server list redraw when a refresh starts or an answer arrives.
 */
@Stable
class ComposeServerData(override val config: ServerConfig) : ServerData {
    override var isLoading by mutableStateOf(false)
    override var infoPacket by mutableStateOf<ServerPacket.ServerInfoReceivePacket?>(null)
}
