/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

/** Platform persistence used by the single API-owned ConfigIO implementation. */
internal expect object ConfigStorage {
    fun saveConfig(name: String, source: String)
    fun readConfig(name: String): String?
    fun deleteConfig(name: String)
    fun saveSingleConfig(group: String, key: String, value: String)
    fun readSingleConfig(group: String, key: String): String?
}
