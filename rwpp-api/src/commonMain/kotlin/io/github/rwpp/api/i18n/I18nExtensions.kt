/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

@file:JvmName("i18nKt")

package io.github.rwpp.api.i18n

import com.corrodinggames.rts.gameFramework.localization.LocaleEngine
import io.github.rwml.api.Rwml

/** Reads registered properties through the native locale engine once the game is ready. */
fun readI18n(path: String, vararg args: Any?): String {
    // Android's launcher can render before the game DEX has been mounted.
    val messages = Rwml.get()?.ui()?.messages()
    if (messages != null && !messages.nativeReady()) return messages.text(path, *args)
    return LocaleEngine.getString(path, *args)
}
