/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.map

import com.corrodinggames.rts.gameFramework.localization.LocaleEngine
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.InputStream

open class Mission(
    id: Int,
    val name: String,
    val type: MissionType,
    mapName: String,
    mapType: GameSetupMapType,
    inputStreamProvider: () -> InputStream,
    enginePath: String = "maps/${type.pathName()}/$mapName.tmx",
) : GameMap(id, mapName, mapType, inputStreamProvider, enginePath) {
    override fun displayName(): String {
        val keyName = missionLocaleMapName(mapName)
        val translated = LocaleEngine.translateMapName(keyName)
        return if (translated == keyName) MapNames.getPrettyMapName(name) else translated
    }
}

/** Native mission translation keys retain mission_N__-__ while excluding sorting/demo metadata. */
internal fun missionLocaleMapName(mapName: String): String = mapName
    .substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("^l\\d+;", RegexOption.IGNORE_CASE), "")
    .replace(Regex("^\\[[^]]*]"), "")
    .replace(Regex("\\.tmx$", RegexOption.IGNORE_CASE), "")
