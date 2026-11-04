/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.util.concurrent.TimeUnit

/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */


class MainTest {
    @Test
    fun test() {

        searchBBS(
            bbsId = "4",
            keyword = "红警",
            page = "1"
        ) { result ->
            result.onSuccess { responseBody ->
                println("请求成功：\n$responseBody")
                // 这里可以添加 JSON 解析逻辑
            }.onFailure { exception ->
                println("请求失败：${exception.message}")
            }
        }
    }

    val okHttpClient = OkHttpClient().newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 封装 API 请求方法
    fun searchBBS(
        bbsId: String? = null,
        keyword: String? = null,
        page: String? = null,
        callback: (Result<String>) -> Unit
    ) {
        // 构建 multipart/form-data 请求体
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                bbsId?.takeIf { it.isNotEmpty() }?.let {
                    addFormDataPart("bbs_id", it)
                }
                keyword?.takeIf { it.isNotEmpty() }?.let {
                    addFormDataPart("keyword", it)
                }
                page?.takeIf { it.isNotEmpty() }?.let {
                    addFormDataPart("page", it)
                }
            }
            .build()

        // 构建请求对象
        val request = Request.Builder()
            .url("https://www.rtsbox.cn/api/search_bbs.php")
            .post(requestBody)
            .build()

        // 异步执行请求
//        okHttpClient.newCall(request).enqueue(object : Callback {
//            override fun onFailure(call: Call, e: IOException) {
//                callback(Result.failure(e))
//            }
//
//            override fun onResponse(call: Call, response: Response) {
//                val result = if (response.isSuccessful) {
//                    response.body?.string()?.let {
//                        Result.success(it)
//                    } ?: Result.failure(IOException("Empty response body"))
//                } else {
//                    Result.failure(IOException("HTTP error code: ${response.code}"))
//                }
//                callback(result)
//            }
//        })
        val response = okHttpClient.newCall(request).execute()
        callback(Result.success(response.body?.string() ?: ""))
    }

    @Test
    fun login() {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("username", "")
            .addFormDataPart("password", "")
            .build()
        // 构建请求对象
        val request = Request.Builder()
            .url("https://www.rtsbox.cn/api/login/api.php")
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()

        response.headers.forEach { println(it.toString()) }
        println(response.body?.string() ?: "")
    }

    @Test
    fun userInfo() {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("type", "UserInfo")
            .addFormDataPart("accountID", "124216")
            .build()
        // 构建请求对象
        val request = Request.Builder()
            .url("https://www.rtsbox.cn/api/it_api/data.php")
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()

        response.headers.forEach { println(it.toString()) }
        println(response.body?.string() ?: "")
    }
}