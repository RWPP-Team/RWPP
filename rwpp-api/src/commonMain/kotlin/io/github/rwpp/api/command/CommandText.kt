/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.command

import io.github.rwml.api.i18n.Messages

/**
 * 命令文案。
 *
 * 宿主启动后通过 [attach] 注入自己的 [Messages]，命令即可跟随界面语言；未注入时退回英文，
 * 未收录的键直接返回键名本身，命令仍然可用。
 */
object CommandText {
    @Volatile
    private var messages: Messages? = null

    fun attach(messages: Messages) {
        this.messages = messages
    }

    fun text(key: String, vararg arguments: Any?): String =
        messages?.text(key, *arguments) ?: Messages.fallback(key, *arguments)
}
