/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

import java.io.File
import java.util.*

internal actual object ConfigStorage {
    private val propertiesCache = mutableMapOf<String, Properties>()

    private fun configFile(name: String) = File(System.getProperty("user.dir"), "$name.toml")
    private fun propertiesFile(group: String) = File(System.getProperty("user.dir"), "$group.properties")

    actual fun saveConfig(name: String, source: String) {
        configFile(name).apply {
            parentFile?.mkdirs()
            writeText(source)
        }
    }

    actual fun readConfig(name: String): String? = configFile(name).takeIf(File::exists)?.readText()

    actual fun deleteConfig(name: String) {
        configFile(name).delete()
    }

    actual fun saveSingleConfig(group: String, key: String, value: String) {
        val properties = properties(group)
        synchronized(properties) {
            properties.setProperty(key, value)
            propertiesFile(group).outputStream().use { properties.store(it, null) }
        }
    }

    actual fun readSingleConfig(group: String, key: String): String? = properties(group).getProperty(key)

    private fun properties(group: String): Properties = synchronized(propertiesCache) {
        propertiesCache.getOrPut(group) {
            Properties().apply { propertiesFile(group).takeIf(File::exists)?.inputStream()?.use(::load) }
        }
    }
}
