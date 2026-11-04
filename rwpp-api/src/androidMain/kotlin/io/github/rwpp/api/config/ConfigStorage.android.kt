/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.config

import android.content.Context
import io.github.rwpp.api.appKoin

internal actual object ConfigStorage {
    private fun preferences(name: String) = appKoin.get<Context>().applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    actual fun saveConfig(name: String, source: String) {
        check(preferences(name).edit().putString("src", source).commit()) { "Could not save configuration $name" }
    }

    actual fun readConfig(name: String): String? = preferences(name).getString("src", null)

    actual fun deleteConfig(name: String) {
        check(preferences(name).edit().remove("src").commit()) { "Could not delete configuration $name" }
    }

    actual fun saveSingleConfig(group: String, key: String, value: String) {
        check(preferences(group).edit().putString(key, value).commit()) { "Could not save configuration $group/$key" }
    }

    actual fun readSingleConfig(group: String, key: String): String? = preferences(group).getString(key, null)
}
