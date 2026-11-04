/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.coil

import coil3.ImageLoader
import coil3.fetch.Fetcher
import coil3.key.Keyer
import coil3.request.Options
import com.corrodinggames.rts.appFramework.AppContext
import com.corrodinggames.rts.gameFramework.files.FileLoader
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.utils.Imageable

/** Preview rendering belongs to the client; API maps keep their platform-independent identity. */
class MapPreviewFetcherFactory : Fetcher.Factory<GameMap> {
    override fun create(data: GameMap, options: Options, imageLoader: ImageLoader): Fetcher =
        ImageableFetcher(object : Imageable {
            override fun openImageInputStream() = runCatching {
                FileLoader.openAsset(AppContext.getMapImagePath(data.enginePath))
            }.getOrNull()
        })
}

class MapPreviewKeyer : Keyer<GameMap> {
    override fun key(data: GameMap, options: Options): String = mapPreviewKey(data)
}

internal fun mapPreviewKey(data: GameMap): String = "map-preview:${data.mapType}:${data.enginePath}"
