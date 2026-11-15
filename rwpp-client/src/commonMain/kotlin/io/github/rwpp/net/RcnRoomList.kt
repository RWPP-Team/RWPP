/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import io.github.rwpp.api.config.ServerConfig
import io.github.rwpp.api.config.ServerType
import io.github.rwpp.api.net.RoomDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object RcnRoomList {
    const val PROVIDER = "rcnRoomListProvider"
    private const val URL = "https://rwpp-team.top/api/v1/rcn/rooms"
    private val roomCode = Regex("[RS][0-9]+")

    fun config() = ServerConfig(
        ip = URL,
        name = "RCN Room List",
        type = ServerType.RoomList,
        editable = false,
        customRoomListProvider = PROVIDER,
        customRoomHostProtocol = "RCN",
    )

    fun matches(config: ServerConfig): Boolean =
        config.type == ServerType.RoomList && config.customRoomListProvider == PROVIDER

    suspend fun fetch(client: OkHttpClient): List<RoomDescription> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(URL).header("Accept", "application/json").build()
        val call = client.newCall(request)
        call.timeout().timeout(12, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("RCN room list returned HTTP ${response.code}")
            parse(response.body.string())
        }
    }

    internal fun parse(payload: String): List<RoomDescription> {
        val result = Json.parse(payload).asObject()
        val rooms = result.get("rooms")
        if (result.getString("status", "") != "success" || rooms?.isArray != true) {
            throw IOException("Invalid RCN room list response")
        }
        return rooms.asArray().mapNotNull { entry ->
            if (!entry.isObject) return@mapNotNull null
            val room = entry.asObject()
            val id = room.get("id")?.takeIf { it.isString }?.asString() ?: return@mapNotNull null
            if (!roomCode.matches(id)) return@mapNotNull null
            val map = room.get("mapText")?.takeIf { it.isString }?.asString().orEmpty()
            RoomDescription(
                uuid = id,
                creator = room.get("nameText")?.takeIf { it.isString }?.asString().orEmpty().ifBlank { id },
                mapName = map,
                displayMapName = map,
                gameVersion = room.get("versionProtocols")?.toString()?.toIntOrNull() ?: 0,
                version = room.get("version")?.takeIf { it.isString }?.asString().orEmpty(),
                playerCurrentCount = room.get("minPlayer")?.toString()?.toIntOrNull()?.takeIf { it >= 0 },
                playerMaxCount = room.get("maxPlayer")?.toString()?.toIntOrNull()?.takeIf { it > 0 },
                status = "RCN",
                isOpen = true,
                customIp = id,
            )
        }.distinctBy(RoomDescription::uuid)
    }
}
