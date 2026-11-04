/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Color
import android.graphics.Rect
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.map.TileSet
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Terrain semantics used before sampling the original tile pixels. */
internal enum class TerrainWaterTileKind {
    FULL_WATER,
    MIXED_SHORELINE,
    LAND,
    BRIDGE,
    LAVA
}

internal data class TerrainWaterTileTraits(
    val nativeWater: Boolean = false,
    val nativeBridge: Boolean = false,
    val nativeLava: Boolean = false,
    val cliff: Boolean = false,
    val tileSetName: String? = null,
    val imageName: String? = null,
    val waterProperty: Boolean = false,
    val bridgeProperty: Boolean = false,
    val lavaProperty: Boolean = false,
    val lavaCliffProperty: Boolean = false
)

/**
 * A mixed terrain tile is sampled from its original tileset image, never from the generated meta atlas.
 * The rect is copied by the native provider because TileSet reuses the Rect returned by its cache.
 */
internal class TerrainWaterTilePixelSource(
    rect: Rect,
    val pixelAt: (x: Int, y: Int) -> Int
) {
    val rect: Rect = Rect(rect)
}

/** Row-major tile coverage, layer opacity, and lava coverage; every channel is in [0, 1]. */
internal data class TerrainWaterRasterSample(
    val resolution: Int,
    val kind: TerrainWaterTileKind,
    val waterCoverage: FloatArray,
    val opacity: FloatArray,
    val lava: FloatArray
) {
    init {
        require(resolution > 0)
        require(waterCoverage.size == resolution * resolution)
        require(opacity.size == resolution * resolution)
        require(lava.size == resolution * resolution)
    }
}

internal fun terrainWaterTileKind(traits: TerrainWaterTileTraits): TerrainWaterTileKind {
    val names = listOfNotNull(traits.tileSetName, traits.imageName).map { it.lowercase(Locale.ROOT) }
    val contains = { needle: String -> names.any { needle in it } }
    val isLava = traits.nativeLava || traits.lavaProperty || traits.lavaCliffProperty || contains("lava")
    val isLavaCliff = traits.cliff || traits.lavaCliffProperty || contains("lava-cliff")
    if (isLava) return if (isLavaCliff) TerrainWaterTileKind.LAND else TerrainWaterTileKind.LAVA

    val isBridge = traits.nativeBridge || traits.bridgeProperty || contains("bridge")
    if (isBridge) return TerrainWaterTileKind.BRIDGE

    val isWaterName = contains("water")
    val isTransition = listOf("ridge", "transition", "shore", "coast", "bank", "edge", "flat", "mixed")
        .any(contains) || contains("2shallowwater") || contains("2deepwater")
    // Some maps incorrectly mark shoreline atlas cells as native water. The source atlas wins.
    if (isWaterName && isTransition) return TerrainWaterTileKind.MIXED_SHORELINE

    if (traits.nativeWater) return TerrainWaterTileKind.FULL_WATER

    val baseImageNames = names.map { it.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
    if (baseImageNames.any { it in PURE_WATER_TILE_NAMES }) return TerrainWaterTileKind.FULL_WATER
    if (traits.waterProperty || isWaterName) return TerrainWaterTileKind.MIXED_SHORELINE
    return TerrainWaterTileKind.LAND
}

/**
 * Weakly caches samples by native MapTile and requested grid resolution. Native solid-water tiles
 * ignore artwork color; mixed tiles use color only inside explicitly water-labelled atlases.
 */
internal class TerrainWaterTileSampler(
    private val traitsForTile: (MapTile) -> TerrainWaterTileTraits = ::nativeTerrainWaterTraits,
    private val pixelsForTile: (MapTile) -> TerrainWaterTilePixelSource? = ::nativeTerrainWaterPixels
) {
    private val cache = WeakHashMap<MapTile, MutableMap<Int, TerrainWaterRasterSample>>()

    @Synchronized
    fun sample(tile: MapTile, resolution: Int): TerrainWaterRasterSample {
        require(resolution in VALID_RESOLUTIONS) { "Unsupported terrain-water sample resolution: $resolution" }
        val tileSamples = cache.getOrPut(tile) { mutableMapOf() }
        return tileSamples.getOrPut(resolution) {
            val kind = terrainWaterTileKind(traitsForTile(tile))
            val pixels = pixelsForTile(tile)
            rasterizeTerrainTile(kind, resolution, pixels)
        }
    }

    fun sampleForMap(tile: MapTile, mapWidth: Int, mapHeight: Int): TerrainWaterRasterSample =
        sample(tile, terrainWaterSampleResolution(mapWidth, mapHeight))
}

/** Limit the GPU mask to the adaptive sizes used by the whole-map terrain pass. */
internal fun terrainWaterSampleResolution(mapWidth: Int, mapHeight: Int): Int {
    require(mapWidth > 0 && mapHeight > 0)
    return when (max(mapWidth, mapHeight)) {
        in 1..128 -> 16
        in 129..256 -> 8
        in 257..512 -> 4
        in 513..1024 -> 2
        else -> 1
    }
}

internal fun rasterizeTerrainTile(
    kind: TerrainWaterTileKind,
    resolution: Int,
    source: TerrainWaterTilePixelSource?
): TerrainWaterRasterSample {
    require(resolution in VALID_RESOLUTIONS)
    val size = resolution * resolution
    if (source == null || source.rect.width() <= 0 || source.rect.height() <= 0) {
        val water = if (kind == TerrainWaterTileKind.FULL_WATER) 1f else 0f
        val lava = if (kind == TerrainWaterTileKind.LAVA) 1f else 0f
        return TerrainWaterRasterSample(
            resolution,
            kind,
            FloatArray(size) { water },
            FloatArray(size) { 1f },
            FloatArray(size) { lava }
        )
    }

    val rect = source.rect
    val tileWidth = rect.width().toFloat()
    val tileHeight = rect.height().toFloat()
    val waterCoverage = FloatArray(size)
    val opacity = FloatArray(size)
    val lavaCoverage = FloatArray(size)
    val tileArea = tileWidth * tileHeight

    for (outY in 0 until resolution) for (outX in 0 until resolution) {
        val left = rect.left + outX * tileWidth / resolution
        val right = rect.left + (outX + 1) * tileWidth / resolution
        val top = rect.top + outY * tileHeight / resolution
        val bottom = rect.top + (outY + 1) * tileHeight / resolution
        var alphaWeight = 0f
        var waterAlphaWeight = 0f
        var lavaAlphaWeight = 0f

        for (sourceY in floor(top).toInt() until ceil(bottom).toInt()) {
            val yWeight = max(0f, min(bottom, sourceY + 1f) - max(top, sourceY.toFloat()))
            if (yWeight == 0f) continue
            for (sourceX in floor(left).toInt() until ceil(right).toInt()) {
                val xWeight = max(0f, min(right, sourceX + 1f) - max(left, sourceX.toFloat()))
                val pixelWeight = xWeight * yWeight
                if (pixelWeight == 0f) continue

                val pixel = source.pixelAt(sourceX, sourceY)
                val rgb = pixel and 0x00ffffff
                val alpha = if (rgb == TRANSPARENT_COLOR_KEY) 0f else Color.alpha(pixel) / 255f
                val pixelWater = when (kind) {
                    TerrainWaterTileKind.FULL_WATER -> 1f
                    TerrainWaterTileKind.MIXED_SHORELINE -> terrainBlueWaterCoverage(pixel)
                    TerrainWaterTileKind.LAND, TerrainWaterTileKind.BRIDGE, TerrainWaterTileKind.LAVA -> 0f
                }
                val pixelLava = if (kind == TerrainWaterTileKind.LAVA) 1f else 0f
                alphaWeight += alpha * pixelWeight
                waterAlphaWeight += pixelWater * alpha * pixelWeight
                lavaAlphaWeight += pixelLava * alpha * pixelWeight
            }
        }

        val index = outY * resolution + outX
        opacity[index] = (alphaWeight / (tileArea / size)).coerceIn(0f, 1f)
        if (alphaWeight > 0f) {
            // These are conditional coverages; multiplying by opacity yields premultiplied coverage
            // for standard alpha-over composition while preserving transparent atlas gaps.
            waterCoverage[index] = (waterAlphaWeight / alphaWeight).coerceIn(0f, 1f)
            lavaCoverage[index] = (lavaAlphaWeight / alphaWeight).coerceIn(0f, 1f)
        }
    }

    return TerrainWaterRasterSample(resolution, kind, waterCoverage, opacity, lavaCoverage)
}

private fun terrainBlueWaterCoverage(argb: Int): Float {
    if ((argb and 0x00ffffff) == TRANSPARENT_COLOR_KEY || Color.alpha(argb) == 0) return 0f
    val blueExcess = (Color.blue(argb) - max(Color.red(argb), Color.green(argb))) / 255f
    val t = ((blueExcess - 0.005f) / (0.055f - 0.005f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun nativeTerrainWaterTraits(tile: MapTile): TerrainWaterTileTraits {
    val tileSet = tile.tileSet
    val properties = if (tileSet == null) null else tileSet.getTileProperties(tileSet.firstGid + tile.tileIndex)
    return TerrainWaterTileTraits(
        nativeWater = tile.water,
        nativeBridge = tile.waterBridge,
        nativeLava = tile.lava,
        cliff = tile.cliff,
        tileSetName = tileSet?.name,
        imageName = tileSet?.imageName,
        waterProperty = properties?.getProperty("water") != null,
        bridgeProperty = properties?.getProperty("water-bridge") != null || properties?.getProperty("bridge") != null,
        lavaProperty = properties?.getProperty("lava") != null,
        lavaCliffProperty = properties?.getProperty("lava-cliff") != null
    )
}

private fun nativeTerrainWaterPixels(tile: MapTile): TerrainWaterTilePixelSource? {
    val tileSet: TileSet = tile.tileSet ?: return null
    val texture: BitmapOrTexture = tileSet.tilesetBitmap ?: return null
    val bitmap = texture.loadedImage ?: texture
    val rect = Rect(tileSet.getCachedTileRect(tile.tileIndex))
    if (rect.left < 0 || rect.top < 0 || rect.right > bitmap.width || rect.bottom > bitmap.height) return null
    bitmap.ensurePixelData()
    return TerrainWaterTilePixelSource(rect) { x, y -> bitmap.getPixel(x, y) }
}

private const val TRANSPARENT_COLOR_KEY = 0x008a76
private val VALID_RESOLUTIONS = setOf(1, 2, 4, 8, 16)
private val PURE_WATER_TILE_NAMES = setOf("water", "shallowwater", "deepwater")
