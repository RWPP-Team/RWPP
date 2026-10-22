/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.map

/** Pure map-file name formatting shared by Android and desktop game adapters. */
object MapNames {
    private val patterns = listOf(
        Regex("^l\\d*;\\[.*\\](.+)\\.tmx$"),
        Regex("^l\\d*;(.+)\\.tmx$"),
        Regex("^ *\\[.*\\](.+)\\.tmx$"),
        Regex("^(.*)\\.tmx$"),
    )

    fun getPrettyMapName(path: String?): String? {
        if (path == null) return null

        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val prettyName = patterns.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(fileName)?.groupValues?.get(1)?.capitalizeFirst()
        } ?: fileName

        return prettyName.replace('_', ' ').removeSuffix(".rwsave")
    }

    private fun String.capitalizeFirst(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
