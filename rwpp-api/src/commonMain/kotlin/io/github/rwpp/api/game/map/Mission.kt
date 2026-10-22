/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.map

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.InputStream

open class Mission(
    id: Int,
    val name: String,
    val type: MissionType,
    mapName: String,
    mapType: GameSetupMapType,
    inputStreamProvider: () -> InputStream,
    displayNameProvider: (() -> String)? = null,
    enginePath: String = "maps/${type.pathName()}/$mapName.tmx",
) : GameMap(id, mapName, mapType, inputStreamProvider, displayNameProvider, enginePath)
