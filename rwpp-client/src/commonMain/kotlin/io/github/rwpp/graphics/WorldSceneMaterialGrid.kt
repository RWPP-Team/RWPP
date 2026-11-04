/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.gameFramework.GameEngine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.corrodinggames.rts.game.map.Map as NativeMap

/** View-local terrain material grid with a sampling margin; fog never gates its coverage. */
internal class WorldSceneMaterialGrid {
    private var nativeMap: NativeMap? = null
    var width = 0; private set
    var height = 0; private set
    var originX = 0; private set
    var originY = 0; private set
    var resolution = 1; private set
    var pixels = IntArray(0); private set
    var version = 0L; private set
    private var sampler = TerrainWaterTileSampler()
    private val workspace = WorldTerrainRasterWorkspace()
    private var signature = 0L
    fun clear() {
        nativeMap = null; width = 0; height = 0; originX = 0; originY = 0; signature = 0; resolution = 1
        pixels = IntArray(0); sampler = TerrainWaterTileSampler(); version++
        workspace.clear()
    }

    fun coversView(engine: GameEngine, map: NativeMap): Boolean {
        if (width == 0 || map.tileWidth <= 0 || map.tileHeight <= 0) return false
        val zoom = engine.zoomScale.coerceAtLeast(0.05f)
        val w = width / resolution;
        val h = height / resolution
        return (originX == 0 || engine.cameraRenderX / map.tileWidth >= originX + 2) &&
                (originY == 0 || engine.cameraRenderY / map.tileHeight >= originY + 2) &&
                (originX + w == map.mapWidthInTiles || (engine.cameraRenderX + engine.screenWidth / zoom) / map.tileWidth <= originX + w - 2) &&
                (originY + h == map.mapHeightInTiles || (engine.cameraRenderY + engine.screenHeight / zoom) / map.tileHeight <= originY + h - 2)
    }

    fun update(engine: GameEngine, map: NativeMap): Boolean {
        if (map.tileWidth <= 0 || map.tileHeight <= 0) return false
        val zoom = engine.zoomScale.coerceAtLeast(0.05f)
        val left = (floor(engine.cameraRenderX / map.tileWidth).toInt() - 7).coerceIn(0, map.mapWidthInTiles)
        val top = (floor(engine.cameraRenderY / map.tileHeight).toInt() - 7).coerceIn(0, map.mapHeightInTiles)
        val right = (ceil((engine.cameraRenderX + engine.screenWidth / zoom) / map.tileWidth).toInt() + 7).coerceIn(
            left,
            map.mapWidthInTiles
        )
        val bottom = (ceil((engine.cameraRenderY + engine.screenHeight / zoom) / map.tileHeight).toInt() + 7).coerceIn(
            top,
            map.mapHeightInTiles
        )
        val w = right - left;
        val h = bottom - top
        if (w <= 0 || h <= 0) return false
        var r = terrainWaterSampleResolution(map.mapWidthInTiles, map.mapHeightInTiles)
        while (w.toLong() * h * r * r > 4_194_304L && r > 1) r /= 2
        if (w.toLong() * h * r * r > 4_194_304L) return false
        val layers = worldTerrainLayers(map)
        var next = 1L
        for (layer in layers) {
            next = 31 * next + System.identityHashCode(layer)
            for (y in top until bottom) for (x in left until right) {
                val tile = if (x < layer.width && y < layer.height) layer.getTile(x, y) else null
                next = 31 * next + System.identityHashCode(tile)
                if (tile != null) next =
                    31 * next + tile.tileIndex + (if (tile.water) 1 else 0) + (if (tile.waterBridge) 2 else 0) +
                            (if (tile.cliff) 4 else 0) + (if (tile.lava) 8 else 0)
            }
        }
        if (nativeMap === map && signature == next && originX == left && originY == top && width == w * r && height == h * r && resolution == r) {
            return true
        }
        if (nativeMap !== map) sampler = TerrainWaterTileSampler()
        val sample = sampleWorldTerrain(layers, sampler, left, top, w, h, r, workspace)
        nativeMap = map; signature = next; originX = left; originY = top; resolution = r; width = w * r; height = h * r
        if (pixels.size != width * height) pixels = IntArray(width * height)
        for (i in pixels.indices) {
            pixels[i] = worldSceneMaterialPixel(
                sample.coverage[i],
                sample.distances[i],
                sample.lava[i],
                sample.cliffs[i]
            )
        }
        version++
        return true
    }
}

internal fun worldSceneMaterialPixel(water: Boolean, coast: Float): Int =
    worldSceneMaterialPixel(if (water) 1f else 0f, coast, 0f, false)

/** Opaque transport avoids Android's alpha premultiplication corrupting material data. */
internal fun worldSceneMaterialPixel(water: Float, coast: Float, lava: Float, cliff: Boolean): Int =
    (255 shl 24) or ((water.coerceIn(0f, 1f) * 255f).roundToInt() shl 16) or
            ((((coast.coerceIn(-6f, 6f) / 12f + 0.5f) * 255f).roundToInt()) shl 8) or
            (if (cliff) 64 else 0) or (lava.coerceIn(0f, 1f) * 63f).roundToInt()
