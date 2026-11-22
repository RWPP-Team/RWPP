/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.command

import io.github.rwml.api.i18n.Messages
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

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

    private val english by lazy { bundle("en") }
    private val chinese by lazy { bundle("zh") }

    fun text(key: String, vararg arguments: Any?): String {
        val host = messages
        val locale = host?.locale() ?: Locale.getDefault()
        val text = (if (locale.language == "zh") chinese.getProperty(key) else null) ?: english.getProperty(key)
        if (text == null) return host?.text(key, *arguments) ?: Messages.fallback(key, *arguments)
        return if (arguments.isEmpty()) text else MessageFormat(text, locale).format(arguments)
    }

    private fun bundle(language: String): Properties = Properties().apply {
        checkNotNull(CommandText::class.java.getResourceAsStream("/io/github/rwpp/api/command/$language.properties"))
            .bufferedReader(Charsets.UTF_8).use(::load)
    }
}
