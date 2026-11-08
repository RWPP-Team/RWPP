/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.map

import java.util.*

/** Pure map-file name formatting shared by Android and desktop game adapters. */
object MapNames {
    /**
     * Map entries produced by ModManager carry virtual filesystem tags such as
     * `MOD|<modHash>/<relativePath>`. They are engine routing data, never user-visible text.
     * The tag can appear after a directory prefix, so the first tag wins.
     */
    fun stripVirtualMapPrefix(path: String): String {
        val normalized = path.replace('\\', '/')
        val start = listOf("MOD|", "NEW_PATH|")
            .map { normalized.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return path
        val tagged = normalized.substring(start)
        val separator = tagged.indexOf('/')
        // `MOD|<hash>` alone has nothing to strip; a trailing path is what the user reads.
        if (separator < 0) return path
        return tagged.substring(separator + 1)
    }

    fun getPrettyMapName(path: String): String {
        val visible = stripVirtualMapPrefix(path)
        val fileName = visible.substringAfterLast('/').substringAfterLast('\\')
        val prettyName = fileName.removeSuffix(".tmx").removeSuffix(".rwsave")
            .replace(Regex("^\\[.*?]"), "")
            .replace('_', ' ')
            .trim()
        // A name that is only the virtual tag keeps the raw file name rather than an empty label.
        if (prettyName.isEmpty()) return path.substringAfterLast('/').substringAfterLast('\\')
        return prettyName.replaceFirstChar {
            // The map name is part of the room setup. A default locale (for example Turkish)
            // changes the capitalised form on one client only.
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }
}
