/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import io.github.rwpp.api.net.Net
import okhttp3.Call
import okhttp3.Callback
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
) {
    val request = Request.Builder()
        .url(protocol.url)
        .post(protocol.requestBodyProvider(page, keyword, type))
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            callback(Result.failure(e))
        }

        override fun onResponse(call: Call, response: okhttp3.Response) {
            val result = if (response.isSuccessful) {
                protocol.resultParser(response)?.let {
                    Result.success(it)
                } ?: Result.failure(IOException("Unknown response body"))
            } else {
                Result.failure(IOException("HTTP error code: ${response.code}"))
            }
            callback(result)
        }
    })
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
        runCatching {
            val jsonBody = Json.parse(response.body?.string()).asObject()
            var id = 0
            buildList {
                for (data in jsonBody.get("data").asArray()) {
                    val info = data.asObject()
                    val title = String(info.getString("title", "???").toByteArray(), Charsets.UTF_8)
                    add(
                        NetResourceInfo(
                            title + id,
                            title,
                            bbsUrl = info.getString("bbsurl", "???"),
                            imageUrl = info.getString("img", null),
                        ),
                    )
                    id++
                }
            }.toTypedArray()
        }.getOrNull()
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
                    val title = String(info.getString("title", "???").toByteArray(), Charsets.UTF_8)
                    val postID = info.getInt("postID", -1)
                    add(
                        NetResourceInfo(
                            postID.toString(),
                            title,
                            bbsUrl = "https://www.rtsbox.cn/$postID.html",
                            imageUrl = info.getString("img_url", null),
                            downloadNum = info.getInt("download_num", -1),
                        ),
                    )
                }
            }.toTypedArray()
        }.getOrNull()
    },
)
