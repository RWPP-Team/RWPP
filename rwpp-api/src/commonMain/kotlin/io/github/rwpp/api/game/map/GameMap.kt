/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.map

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.InputStream

/** A concrete map entry shared by the API; hosts provide only the platform-specific stream readers. */
open class GameMap(
    val id: Int,
    val mapName: String,
    val mapType: GameSetupMapType,
    private val inputStreamProvider: () -> InputStream,
    val enginePath: String = defaultMapPath(mapName, mapType),
) {
    open fun openInputStream(): InputStream = inputStreamProvider()

    private val _displayName = MapNames.getPrettyMapName(mapName)
    open fun displayName(): String = _displayName

    fun getMapSuffix() = if (mapType == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
}

internal fun defaultMapPath(name: String, type: GameSetupMapType): String {
    val suffix = if (type == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
    val normalizedName = name.replace('\\', '/')
    val fileName = if (normalizedName.endsWith(suffix, ignoreCase = true)) normalizedName else normalizedName + suffix
    if (fileName.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(fileName)) return fileName
    if (listOf(
            "maps/",
            "mods/maps/",
            "saves/",
            "/SD/rusted_warfare_maps/",
            "/SD/rustedWarfare/saves/",
            "/SD/rustedWarfare/maps/",
        ).any { fileName.startsWith(it, ignoreCase = true) }
    ) {
        return fileName
    }
    val directory = when (type) {
        GameSetupMapType.skirmishMap -> "maps/skirmish"
        GameSetupMapType.customMap -> "/SD/rusted_warfare_maps"
        GameSetupMapType.savedGame -> "saves"
    }
    return "$directory/$fileName"
}

/** Normalize separators without discarding folder names or native virtual-path tags. */
internal fun normalizeGameMapPath(path: String): String = path
    .replace('\\', '/')
    .replace(Regex("/{2,}"), "/")
    .removePrefix("./")
    .trimEnd('/')

/** Compare full engine paths; callers must not reduce a path to its basename. */
internal fun sameGameMapPath(first: String, second: String): Boolean =
    normalizeGameMapPath(first).equals(normalizeGameMapPath(second), ignoreCase = true)

internal fun sameGameMapName(first: String, second: String): Boolean =
    withoutGameMapSuffix(normalizeGameMapPath(first)).equals(
        withoutGameMapSuffix(normalizeGameMapPath(second)),
        ignoreCase = true,
    )

private fun withoutGameMapSuffix(value: String): String = when {
    value.endsWith(".tmx", ignoreCase = true) -> value.dropLast(4)
    value.endsWith(".rwsave", ignoreCase = true) -> value.dropLast(7)
    else -> value
}
