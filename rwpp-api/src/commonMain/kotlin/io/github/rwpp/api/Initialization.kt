/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

/**
 * 由宿主在启动阶段统一回调的初始化接口。
 *
 * 扩展把需要延后到 Koin 容器就绪之后才能执行的装配放进这里，宿主只调用一次 [init]。
 */
interface Initialization {
    fun init()

    /** Called by the built-in API extension after the game adapter has finished loading. */
    fun onGameLoaded() = Unit
}
