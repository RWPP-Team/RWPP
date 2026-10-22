/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.coil

import coil3.key.Keyer
import coil3.request.Options
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.ui.Imageable

class ImageableKeyer : Keyer<Imageable> {
    override fun key(data: Imageable, options: Options): String? {
        return when (data) {
            is GameMap -> data.mapName
            else -> null
        }
    }
}