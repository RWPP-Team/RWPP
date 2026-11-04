/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.i18n

import io.github.rwml.api.Rwml
import io.github.rwml.api.i18n.Messages
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.generated.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.util.*

/** Registers RWPP's product-owned translation tables with the shared locale service. */
object ProductTranslations {
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return

        val tables = runBlocking {
            withContext(Dispatchers.IO) {
                mapOf(
                    "en" to loadProperties("files/rwpp_en.properties"),
                    "zh" to loadProperties("files/rwpp_zh.properties")
                )
            }
        }
        val messages = Rwml.get().ui().messages()
        messages.register(object : Messages.Source {
            override fun find(locale: Locale, key: String): String? {
                if (!key.startsWith("rwpp.")) return null
                val language = locale.language.lowercase(Locale.ROOT)
                return tables[language]?.getProperty(key) ?: tables.getValue("en").getProperty(key)
            }

            override fun keys(locale: Locale): Set<String> = buildSet {
                addAll(tables.getValue("en").stringPropertyNames())
                tables[locale.language.lowercase(Locale.ROOT)]?.let {
                    addAll(it.stringPropertyNames())
                }
            }
        })
        installed = true

        val settings = appKoin.get<Settings>()
        if (settings.forceEnglish && messages.language() == "system") {
            messages.language("en")
        }
        settings.forceEnglish = messages.language() == "en"
    }

    private suspend fun loadProperties(path: String): Properties {
        val content = Res.readBytes(path).decodeToString()
        return Properties().apply { load(StringReader(content)) }
    }
}
