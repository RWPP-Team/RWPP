/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.map

import java.text.SimpleDateFormat
import java.util.*

class Replay(
    val id: Int,
    val name: String,
    private val displayNameProvider: (() -> String)? = null,
) {
    /** Filename timestamp used for matching old recordings that lack native metadata. */
    val timestampMillis: Long? = replayTimestampMillis(name)

    fun displayName(): String = displayNameProvider?.invoke() ?: name
}

private val replayTimestampPattern = Regex(".*\\((.*)\\).*")
private val replayTimestampFormats = listOf(
    "d MMM yyyy HH.mm.ss",
    "d MMM yyyy HH:mm:ss",
    "d MMM yyyy HH_mm_ss",
    "d MMM yyyy HH-mm-ss",
    "d MMM. yyyy HH.mm.ss",
)

private fun replayTimestampMillis(name: String): Long? {
    val value = replayTimestampPattern.matchEntire(name)?.groupValues?.getOrNull(1) ?: return null
    for (format in replayTimestampFormats) {
        val date = runCatching { SimpleDateFormat(format, Locale.ENGLISH).parse(value) }.getOrNull()
        if (date != null) return date.time
    }
    return null
}
