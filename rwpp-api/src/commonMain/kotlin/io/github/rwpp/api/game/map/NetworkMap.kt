/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.map

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A map advertised by the active network setup but not present in the regular local map listing.
 * The engine can still provide its built-in asset or its downloaded custom map through FileLoader.
 */
class NetworkMap(
    value: String,
    type: GameSetupMapType = currentMapType(value),
) : GameMap(
    id = -1,
    mapName = mapNameFromPath(value, type),
    mapType = type,
    inputStreamProvider = { openNetworkMap(value, mapNameFromPath(value, type), type) },
    displayNameProvider = { mapNameFromPath(value, type).substringAfterLast('/') },
    enginePath = defaultMapPath(value, type),
) {
    /** Network map entries have no separate preview image. */
    fun openImageInputStream(): InputStream? = null
}

private fun currentMapType(value: String): GameSetupMapType {
    val network = GameEngine.getInstance().networkEngine
    val configuredType = network.gameSetup.mapType ?: GameSetupMapType.customMap
    val configuredPath = network.getNetworkMapPath()
    return if (sameMapName(network.gameSetup.mapName, value, configuredType) ||
        (!configuredPath.isNullOrBlank() && sameMapName(configuredPath, value, configuredType))
    ) {
        configuredType
    } else {
        GameSetupMapType.customMap
    }
}

private fun openNetworkMap(value: String, mapName: String, type: GameSetupMapType): InputStream {
    val network = GameEngine.getInstance().networkEngine
    val setupName = network.gameSetup.mapName
    val currentPath = network.currentGameMapPath
    val hasMatchingSetup = sameMapName(setupName, mapName, type)
    val candidates = linkedSetOf<String>()

    // Prefer the engine's resolved path when it names this map; it may point to a downloaded or
    // mod-provided file that is not covered by the regular built-in/custom map directories.
    if (!currentPath.isNullOrBlank() &&
        (sameMapName(currentPath, mapName, type) || sameMapName(currentPath, setupName, type))
    ) {
        candidates += currentPath
    }
    if (hasMatchingSetup) {
        network.getNetworkMapPath()?.takeIf(String::isNotBlank)?.let(candidates::add)
    }

    val normalizedValue = value.replace('\\', '/')
    if ('/' in normalizedValue || '\\' in value) candidates += normalizedValue

    val mapFileName = fileNameWithMapSuffix(value, type)
    val preferredPaths = when (type) {
        GameSetupMapType.skirmishMap -> listOf("maps/skirmish/$mapFileName")
        GameSetupMapType.customMap -> listOf(
            "/SD/rusted_warfare_maps/$mapFileName",
            "mods/maps/$mapFileName",
            "/SD/rusted_warfare/maps/$mapFileName",
        )
        GameSetupMapType.savedGame -> listOf(
            "/SD/rustedWarfare/saves/$mapFileName",
            "saves/$mapFileName",
        )
        else -> emptyList()
    }
    candidates += preferredPaths

    // Replay metadata can refer to a map from a different category than the currently selected
    // room map. Try the remaining engine locations after the setup's declared type.
    candidates += listOf(
        "maps/skirmish/${fileNameWithMapSuffix(value, GameSetupMapType.skirmishMap)}",
        "/SD/rusted_warfare_maps/${fileNameWithMapSuffix(value, GameSetupMapType.customMap)}",
        "mods/maps/${fileNameWithMapSuffix(value, GameSetupMapType.customMap)}",
        "/SD/rustedWarfare/saves/${fileNameWithMapSuffix(value, GameSetupMapType.savedGame)}",
        "saves/${fileNameWithMapSuffix(value, GameSetupMapType.savedGame)}",
    )

    var lastFailure: Exception? = null
    for (path in candidates) {
        try {
            FileLoader.openByPath(path)?.let { return it }
        } catch (failure: Exception) {
            lastFailure = failure
        }
    }

    val failure = FileNotFoundException(
        "Map '$mapName' is not available locally. It may not have been downloaded, or its file cannot be read. " +
            "Checked: ${candidates.joinToString()}",
    )
    lastFailure?.let(failure::initCause)
    throw failure
}

private fun fileNameWithMapSuffix(value: String, type: GameSetupMapType): String {
    val name = mapPathWithoutCategoryRoot(value, type)
    if (name.endsWith(".tmx", ignoreCase = true) || name.endsWith(".rwsave", ignoreCase = true)) {
        return name
    }
    val suffix = if (type == GameSetupMapType.savedGame) ".rwsave" else ".tmx"
    return name + suffix
}

private fun mapNameFromPath(value: String, type: GameSetupMapType): String =
    mapPathWithoutCategoryRoot(value, type).removeMapSuffix()

private fun mapPathWithoutCategoryRoot(value: String, type: GameSetupMapType): String {
    val normalized = normalizeGameMapPath(value)
    val prefixes = when (type) {
        GameSetupMapType.skirmishMap -> listOf("maps/skirmish/")
        GameSetupMapType.customMap -> listOf("/SD/rusted_warfare_maps/", "mods/maps/")
        GameSetupMapType.savedGame -> listOf("/SD/rustedWarfare/saves/", "saves/")
        else -> emptyList()
    }
    val prefix = prefixes.firstOrNull { normalized.startsWith(it, ignoreCase = true) }
    return if (prefix == null) normalized else normalized.drop(prefix.length)
}

private fun sameMapName(
    first: String?,
    second: String?,
    type: GameSetupMapType,
): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    return mapNameFromPath(first, type).equals(mapNameFromPath(second, type), ignoreCase = true)
}

private fun String.removeMapSuffix(): String = when {
    endsWith(".tmx", ignoreCase = true) -> dropLast(4)
    endsWith(".rwsave", ignoreCase = true) -> dropLast(7)
    else -> this
}
