/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import io.github.rwpp.api.net.Net
import okhttp3.Request
import java.util.Locale

data class LatestVersionProfile(val version: String, val body: String, val prerelease: Boolean)

/** 读取 RWPP 仓库最新发行版的信息。 */
fun Net.getLatestVersionProfile(): LatestVersionProfile? = runCatching {
    val locale = Locale.getDefault()
    val request = Request.Builder().url(
        if (locale.country == "CN") {
            "https://gitee.com/api/v5/repos/minxyzgo/RWPP/releases/latest"
        } else {
            "https://api.github.com/repos/Minxyzgo/RWPP/releases/latest"
        },
    ).build()
    val response = client.newCall(request).execute()

    response.body.string().let { str ->
        val version = Json.parse(str).asObject().getString("tag_name", "null")
        val body = Json.parse(str).asObject().getString("body", "null")
        val prerelease = Json.parse(str).asObject().getBoolean("prerelease", false)
        LatestVersionProfile(version, body, prerelease)
    }
}.getOrNull()