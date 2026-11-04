/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.command

import io.github.rwpp.api.command.CLIENT
import io.github.rwpp.api.command.CommandSender
import io.github.rwpp.api.command.CommandVisibility

/**
 * 把一条回复通道包装成命令发送者。
 *
 * RWPP 运行在游戏进程内，命令由游戏客户端输入，因此只覆盖客户端可见范围。
 */
fun commandSender(
    reply: (String) -> Unit,
    permission: (String) -> Boolean = { true },
): CommandSender = object : CommandSender {
    override fun reply(message: String) = reply(message)

    override fun hasPermission(permission: String): Boolean = permission(permission)

    override fun hasVisibility(visibility: String): Boolean = visibility == CommandVisibility.CLIENT
}
