/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.Map as NativeMap

internal fun worldTerrainLayers(map: NativeMap): List<MapLayer> =
    listOfNotNull(map.groundLayer, map.groundDetailsLayer, map.groundDetails2Layer) +
            map.layers.filterIsInstance<MapLayer>().filter { it.isItemsLayer }

internal data class WorldTerrainRaster(
    val coverage: FloatArray,
    val lava: FloatArray,
    val cliffs: BooleanArray,
    val distances: FloatArray
)

/** Renderer-owned storage avoids allocating large arrays again when the camera moves. */
internal class WorldTerrainRasterWorkspace {
    var coverage = FloatArray(0); private set
    var lava = FloatArray(0); private set
    var opacity = FloatArray(0); private set
    var cliffs = BooleanArray(0); private set
    var water = BooleanArray(0); private set
    var coastScratch = FloatArray(0); private set
    var distances = FloatArray(0); private set
    fun reset(size: Int) {
        if (coverage.size != size) {
            coverage = FloatArray(size); lava = FloatArray(size); opacity = FloatArray(size)
            cliffs = BooleanArray(size); water = BooleanArray(size)
            coastScratch = FloatArray(size); distances = FloatArray(size)
        } else {
            coverage.fill(0f); lava.fill(0f); opacity.fill(0f); cliffs.fill(false)
        }
    }

    fun clear() {
        reset(0)
    }
}

/** Exact native layer order and coverage composition. No renderer, image format or GPU API here. */
internal fun sampleWorldTerrain(
    layers: List<MapLayer>, sampler: TerrainWaterTileSampler,
    originX: Int, originY: Int, tilesWide: Int, tilesHigh: Int, resolution: Int,
    workspace: WorldTerrainRasterWorkspace = WorldTerrainRasterWorkspace()
): WorldTerrainRaster {
    val width = tilesWide * resolution
    val size = width * tilesHigh * resolution
    workspace.reset(size)
    val coverage = workspace.coverage
    val lava = workspace.lava
    val opacity = workspace.opacity
    val cliffs = workspace.cliffs
    for (y in 0 until tilesHigh) for (x in 0 until tilesWide) for (layer in layers) {
        val tx = x + originX
        val ty = y + originY
        if (tx !in 0 until layer.width || ty !in 0 until layer.height) continue
        val tile = layer.getTile(tx, ty) ?: continue
        val sample = sampler.sample(tile, resolution)
        for (sy in 0 until resolution) for (sx in 0 until resolution) {
            val local = sy * resolution + sx
            val i = (y * resolution + sy) * width + x * resolution + sx
            val alpha = sample.opacity[local]
            coverage[i] = sample.waterCoverage[local] * alpha + coverage[i] * (1f - alpha)
            lava[i] = sample.lava[local] * alpha + lava[i] * (1f - alpha)
            opacity[i] = alpha + opacity[i] * (1f - alpha)
            if (tile.cliff && alpha >= 0.5f) cliffs[i] = true
        }
    }
    for (i in coverage.indices) if (opacity[i] > 0f) {
        coverage[i] /= opacity[i]; lava[i] /= opacity[i]
    }
    for (i in coverage.indices) workspace.water[i] = coverage[i] >= 0.5f
    val distances = terrainCoastDistances(
        workspace.water,
        width,
        tilesHigh * resolution,
        6f * resolution, workspace.coastScratch, workspace.distances
    )
    for (i in distances.indices) distances[i] /= resolution
    return WorldTerrainRaster(coverage, lava, cliffs, distances)
}
