/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.config

import kotlinx.serialization.Serializable

/**
 * Describes the configuration of the various kinds of server entries.
 *
 * [useAsDefaultList] applies when [type] is [ServerType.RoomList]. While it is true, this list is
 * opened first every time the multiplayer screen starts.
 *
 * Reading the RWPP server-info protocol from a [ServerType.Server] entry is a network operation and
 * therefore belongs to `ServerInfoQuery`, not to this data class.
 */
@Serializable
data class ServerConfig @JvmOverloads constructor(
    var ip: String,
    var name: String,
    var type: ServerType,
    var useAsDefaultList: Boolean = false,
    var editable: Boolean = true,
    var customRoomListProvider: String? = null,
    var customRoomHostProtocol: String? = null,
)
