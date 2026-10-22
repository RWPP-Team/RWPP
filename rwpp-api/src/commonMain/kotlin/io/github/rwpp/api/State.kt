/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api

/**
 * 客户端当前所处的阶段。
 *
 * 界面按这份状态决定显示主菜单还是战斗界面，状态迁移通过 `StateChangedEvent` 广播。
 */
enum class State {
    Paused,
    Playing,
    Menu,
}
