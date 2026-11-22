/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import io.github.rwpp.api.net.Net
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import java.io.IOException

private val registeredProtocols = mutableListOf<BBSProtocol>()

/**
 * 已注册的论坛检索协议。
 */
val Net.bbsProtocols: MutableList<BBSProtocol>
    get() = registeredProtocols

/**
 * 按指定协议检索资源。
 */
fun Net.searchBBS(
    protocol: BBSProtocol,
    page: Int,
    keyword: String,
    type: ResourceType,
    callback: (Result<Array<NetResourceInfo>>) -> Unit,
): Call {
    val request = Request.Builder()
        .url(protocol.url)
        .post(protocol.requestBodyProvider(page, keyword, type))
        .build()

    val call = client.newCall(request)
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            callback(Result.failure(e))
        }

        override fun onResponse(call: Call, response: okhttp3.Response) {
            val result = runCatching {
                response.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    protocol.resultParser(it) ?: throw IOException("Invalid resource response")
                }
            }
            callback(result)
        }
    })
    return call
}

/** RWPP product search protocol for the RTSBox resource forum. */
val RTSBoxProtocol = BBSProtocol(
    "https://www.rtsbox.cn/api/search_bbs.php",
    "铁锈盒子",
    { page, keyword, type ->
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                addFormDataPart("bbs_id", if (type == ResourceType.Mod) "4" else "5")
                addFormDataPart("keyword", keyword)
                addFormDataPart("page", page.toString())
            }
            .build()
    },
    { response ->
        parseRtsBoxSearch(response.body.string())
    },
)

/** RWPP product search protocol for the RTSBox weekly download ranking. */
val RTSBoxDownloadWeeklyProtocol = BBSProtocol(
    "https://www.rtsbox.cn/api/lt_api/data.php",
    "铁锈盒子 下载周榜",
    { page, _, type ->
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                addFormDataPart("catID", if (type == ResourceType.Mod) "mod" else "map")
                addFormDataPart("type", "WeekDownload")
                addFormDataPart("page", page.toString())
            }
            .build()
    },
    { response ->
        runCatching {
            val jsonBody = Json.parse(response.body?.string())
            buildList {
                for (data in jsonBody.asArray()) {
                    val info = data.asObject()
                    val title = info.nonBlank("title") ?: continue
                    val postID = info.getInt("postID", -1)
                    if (postID <= 0) continue
                    add(
                        NetResourceInfo(
                            postID.toString(),
                            title,
                            bbsUrl = "https://www.rtsbox.cn/$postID.html",
                            imageUrl = info.getString("img_url", null),
                            downloadNum = info.getInt("download_num", -1),
                            downloadUrl = info.rtsBoxDownloadUrl(),
                        ),
                    )
                }
            }.toTypedArray()
        }.getOrNull()
    },
)

internal fun parseRtsBoxSearch(body: String): Array<NetResourceInfo>? = runCatching {
    val result = Json.parse(body).asObject()
    when (result.getInt("code", 200)) {
        404 -> emptyArray()
        200 -> result.get("data").asArray().mapNotNull { value ->
            if (!value.isObject) return@mapNotNull null
            val item = value.asObject()
            val title = item.nonBlank("title") ?: return@mapNotNull null
            val page = item.nonBlank("bbsurl")?.toHttpUrlOrNull()?.toString()
            val download = item.rtsBoxDownloadUrl()
            NetResourceInfo(page ?: download ?: title, title, bbsUrl = page, downloadUrl = download,
                imageUrl = item.nonBlank("img"))
        }.distinctBy(NetResourceInfo::id).toTypedArray()
        else -> null
    }
}.getOrNull()

private fun JsonObject.nonBlank(key: String): String? = get(key)?.takeIf { it.isString }
    ?.asString()?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.rtsBoxDownloadUrl(): String? =
    sequenceOf(nonBlank("downurl"), nonBlank("durl"), nonBlank("file_url")).mapNotNull { value ->
        val url = value?.toHttpUrlOrNull() ?: return@mapNotNull null
        when {
            url.isHttps -> url.toString()
            url.host == "cdn.rtsbox.cn" -> url.newBuilder().scheme("https").build().toString()
            else -> null
        }
    }.firstOrNull()
