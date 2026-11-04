/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.game.map.*
import java.util.*

internal class RoomMapCatalogIndex(maps: List<GameMap>) {
    private val bySetupName = LinkedHashMap<RoomMapCatalogKey, GameMap>()
    private val byEnginePath = LinkedHashMap<RoomMapCatalogKey, GameMap>()

    init {
        maps.forEach { map ->
            map.mapName.roomMapCatalogKey(map.mapType)?.let { bySetupName.putIfCatalogKeyMissing(it, map) }
            map.enginePath.roomMapCatalogKey(map.mapType)?.let { key ->
                bySetupName.putIfCatalogKeyMissing(key, map)
            }
            map.enginePath.roomMapCatalogPathKey(map.mapType)?.let { byEnginePath.putIfCatalogKeyMissing(it, map) }
        }
    }

    fun find(type: GameSetupMapType, name: String?, currentPath: String?): GameMap? =
        if (!name.isNullOrBlank()) {
            name.roomMapCatalogKey(type)?.let(bySetupName::get)
        } else {
            currentPath?.roomMapCatalogPathKey(type)?.let(byEnginePath::get)
        }
}

private fun MutableMap<RoomMapCatalogKey, GameMap>.putIfCatalogKeyMissing(key: RoomMapCatalogKey, map: GameMap) {
    if (key !in this) this[key] = map
}

private data class RoomMapCatalogKey(val type: GameSetupMapType, val value: String)

private fun String.roomMapCatalogKey(type: GameSetupMapType): RoomMapCatalogKey? {
    val normalized = normalizeGameMapPath(this)
    val name = when {
        normalized.endsWith(".tmx", ignoreCase = true) -> normalized.dropLast(4)
        normalized.endsWith(".rwsave", ignoreCase = true) -> normalized.dropLast(7)
        else -> normalized
    }.lowercase(Locale.ROOT)
    return name.takeIf(String::isNotBlank)?.let { RoomMapCatalogKey(type, it) }
}

private fun String.roomMapCatalogPathKey(type: GameSetupMapType): RoomMapCatalogKey? {
    val path = normalizeGameMapPath(this).lowercase(Locale.ROOT)
    return path.takeIf(String::isNotBlank)?.let { RoomMapCatalogKey(type, it) }
}

internal fun resolveRoomMap(maps: List<GameMap>, type: GameSetupMapType, name: String?, cachedPath: String?): GameMap =
    maps.firstOrNull {
        it.mapType == type && !name.isNullOrBlank() &&
                (sameGameMapName(it.mapName, name) || sameGameMapName(it.enginePath, name))
    } ?: maps.firstOrNull {
        name.isNullOrBlank() && it.mapType == type && cachedPath != null && sameGameMapPath(it.enginePath, cachedPath)
    } ?: NetworkMap(name?.takeIf(String::isNotBlank) ?: cachedPath.orEmpty(), type)

/** A fresh transport/room must not inherit a departed server's setup or map payload. */
internal fun resetNativeRoomMap(network: NetworkEngine) {
    network.gameSetup = GameSetup()
    network.currentGameMapPath = null
    network.mapName = null
    network.customMapStream = null
    network.savedGameStream = null
}
