/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.utils

import java.util.*

internal fun isNativeUnitSearchPrompt(question: String?): Boolean =
    question == "Search units by internal name or text title."

/** Native prompts may contain a localization key or an arbitrary server/editor sentence. */
internal fun nativePromptText(raw: String?, lookup: (String) -> String): String? {
    if (raw.isNullOrBlank()) return raw
    return try {
        lookup(raw)
    } catch (_: MissingResourceException) {
        raw
    }
}
