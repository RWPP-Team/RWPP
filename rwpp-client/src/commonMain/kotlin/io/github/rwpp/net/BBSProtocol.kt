/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

import okhttp3.RequestBody
import okhttp3.Response

enum class ResourceType {
    Mod,
    Map,
}

class NetResourceInfo(
    val id: String,
    val title: String,
    val description: String? = null,
    val downloadNum: Int? = null,
    val bbsUrl: String? = null,
    val downloadUrl: String? = null,
    val version: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
)

class BBSProtocol(
    val url: String,
    val name: String,
    val requestBodyProvider: (page: Int, keyword: String, type: ResourceType) -> RequestBody,
    val resultParser: (Response) -> Array<NetResourceInfo>?,
)