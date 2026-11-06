/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.corrodinggames.rts.appFramework.AppContext
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.impl.openGamePath
import io.github.rwpp.api.game.impl.openGamePathWithModTag
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.api.logging.logger
import io.github.rwpp.game.map.MapPreviewGenerationService
import io.github.rwpp.generated.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Preview rendering belongs to the client; API maps keep their platform-independent identity. */
class MapPreviewFetcherFactory : Fetcher.Factory<GameMap> {
    override fun create(data: GameMap, options: Options, imageLoader: ImageLoader): Fetcher =
        MapPreviewFetcher(data, ::openMapPreview, ::generateMapPreview)
}

class MapPreviewKeyer : Keyer<GameMap> {
    override fun key(data: GameMap, options: Options): String = mapPreviewKey(data)
}

/** Bump the version when preview resolution changes so stale placeholder caches are dropped. */
private const val MAP_PREVIEW_SCHEMA = 3

internal fun mapPreviewKey(data: GameMap): String =
    "map-preview-v$MAP_PREVIEW_SCHEMA:${data.mapType}:${data.enginePath}"

/**
 * Map lists, the battle room, missions and replays render a GameMap directly. Only the
 * detail sidebar has an explicit generation button, so a map without a PNG beside its
 * TMX would otherwise show the missing-image placeholder everywhere else. Rendering is
 * done here on demand; the keyer keeps one Coil cache entry per map.
 */
internal class MapPreviewFetcher(
    private val map: GameMap,
    private val nativePreview: (GameMap) -> InputStream?,
    private val renderedPreview: suspend (GameMap) -> InputStream?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val stream = withContext(Dispatchers.IO) {
            nativePreview(map) ?: renderedPreview(map)
        }
        if (stream == null) {
            logger().warn("Neither a PNG nor a renderable TMX is available for '{}'", map.enginePath)
        }
        val source = ImageSource(
            (stream ?: ByteArrayInputStream(Res.readBytes("drawable/error_missingmap.png")))
                .source().buffer(),
            fileSystem = FileSystem.SYSTEM,
        )
        return SourceFetchResult(source = source, mimeType = null, dataSource = DataSource.DISK)
    }
}

private suspend fun generateMapPreview(map: GameMap): InputStream? {
    val service = MapPreviewGenerationService()
    if (!service.supports(map)) return null
    return service.generate(map).getOrNull()?.openImageInputStream()
}

/**
 * The native game reads a map image from the abstract file layer, not from the
 * asset stream: this is what lets modded and downloaded maps supply a PNG beside
 * their TMX file. Built-in maps still resolve through the asset fallback.
 */
internal fun openMapPreview(map: GameMap): InputStream? {
    val paths = LinkedHashSet<String>()
    fun add(base: String?) {
        if (base.isNullOrBlank()) return
        val image = runCatching { AppContext.getMapImagePath(base) }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: previewImagePath(base)
        paths += image
        val normalized = image.replace('\\', '/')
        if (normalized.startsWith("/SD/", ignoreCase = true)) {
            // Custom maps are exposed under the virtual SD directory but are stored
            // under mods/maps on desktop. Both spellings are valid engine inputs.
            val fileName = normalized.substringAfterLast('/')
            paths += "mods/maps/$fileName"
            paths += "assets/maps/$fileName"
        }
    }

    add(map.enginePath)
    if (map is NetworkMap) {
        val network = runCatching { GameEngine.getInstance().networkEngine }.getOrNull()
        if (network != null) {
            add(network.currentGameMapPath)
            add(network.getNetworkMapPath())
        }
    }
    // Virtual tags such as MOD| are kept: the native engine resolves the preview
    // beside the tagged map path itself.

    for (path in paths) {
        val stream = runCatching { openGamePath(path) }.getOrNull()
            ?: runCatching { openGamePathWithModTag(path) }.getOrNull()
        if (stream != null) return stream
    }
    // A missing PNG is expected for custom maps; the fetcher renders the TMX instead.
    return null
}

private fun previewImagePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val base = when {
        normalized.endsWith(".tmx", ignoreCase = true) -> normalized.dropLast(4)
        normalized.endsWith(".rwsave", ignoreCase = true) -> normalized.dropLast(7)
        else -> normalized
    }
    return "${base}_map.png"
}
