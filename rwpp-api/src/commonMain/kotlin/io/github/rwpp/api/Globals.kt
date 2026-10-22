/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

import io.github.rwpp.api.command.Commands
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin

/**
 * API 内置扩展启动的共享 Koin 容器。
 */
lateinit var appKoin: Koin
    internal set

/**
 * 跑在游戏线程上的协程作用域，由 API 内置扩展初始化。
 * 引擎相关的调用（重载 mod、重同步存档）必须投放到这里，不能直接在调用者线程上执行。
 */
lateinit var gameScope: CoroutineScope
    internal set

/**
 * 全局命令注册表，宿主与扩展都在这一份实例上注册命令。
 */
val commands = Commands()


/**
 * 房间列表协议里的游戏版本号，1.15 对应 176。
 */
const val gameVersion: Int = 176
