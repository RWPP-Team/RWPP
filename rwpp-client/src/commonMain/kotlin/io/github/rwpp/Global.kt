/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:JvmName("globalKt")
@file:Suppress("ktlint:standard:property-naming")

/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 PolyForm Shield License 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the PolyForm Shield License that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp

import io.github.rwpp.api.appKoin
import org.koin.core.Koin

/**
 * The version of the project.
 */
const val projectVersion = "v2.0.0"

/**
 * The version of the game core.
 */
const val coreVersion = "v1.15"

/**
 * The protocol version.
 * The clients which have different protocol version can not join to each other
 */
const val protocolVersion = 3

/**
 * 1.15 -> 176
 */
const val gameVersion: Int = 176

/**
 * global koin module.
 *
 * 实际存放在 api 的全局里，扩展与宿主共用一个容器。
 */
val appKoin: Koin
    get() = appKoin

val welcomeMessage =
    """
        这是一个使用[RWPP]所创建的房间
        [RWPP]是支持多平台的扩展客户端&服务端
        QQ交流群: 927597495
        源代码地址请访问 https://github.com/RWPP-Team/RWPP 
        当前版本: $projectVersion (core $coreVersion)
        Copyright 2023-2026 RWPP contributors
    """.trimIndent()

const val packageName = "io.github.rwpp"

/**
 * 48 is the maximum size of Rusted Warfare packet in MB.
 */
val maxModSize = 48L * 1024 * 1024


val mapDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("maps")
}

val modDir by lazy {
    appKoin.get<AppContext>().externalStoragePath("units")
}
