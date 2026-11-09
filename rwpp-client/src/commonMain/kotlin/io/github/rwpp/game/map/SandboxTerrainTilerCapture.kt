/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.TileSet
import com.corrodinggames.rts.gameFramework.GameEngine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.*
import com.corrodinggames.rts.game.map.Map as NativeMap

internal data class TerrainTilerDirtyLayer(
    val layer: MapLayer,
    val cells: Set<Int>,
)

internal data class TerrainTilerDirtySnapshot(
    val layers: List<TerrainTilerDirtyLayer>,
)

/** The thread-local scope covers one native TerrainTiler edit and records its exact setTile calls. */
internal object TerrainTilerWriteCapture {
    private class Recording(val map: NativeMap) {
        val cells = IdentityHashMap<MapLayer, MutableSet<Int>>()
        var failed = false
    }

    private data class WeakLayerCells(
        val layer: WeakReference<MapLayer>,
        val cells: Set<Int>,
    )

    private data class PublishedRecording(
        val map: WeakReference<NativeMap>,
        val layers: List<WeakLayerCells>,
        val failed: Boolean,
    )

    private val active = ThreadLocal<Recording?>()
    private var published: PublishedRecording? = null

    fun <T> withScope(map: NativeMap, operation: () -> T): T {
        val parent = active.get()
        if (parent != null) {
            if (parent.map !== map) parent.failed = true
            return try {
                operation()
            } catch (failure: Throwable) {
                parent.failed = true
                throw failure
            }
        }

        val recording = Recording(map)
        active.set(recording)
        try {
            val result = operation()
            if (GameEngine.getInstance().map !== map) recording.failed = true
            publish(recording)
            return result
        } catch (failure: Throwable) {
            recording.failed = true
            publish(recording)
            throw failure
        } finally {
            active.remove()
        }
    }

    fun record(layer: MapLayer, x: Int, y: Int) {
        val recording = active.get() ?: return
        if (layer.map !== recording.map || layer.width <= 0 || layer.height <= 0 ||
            x !in 0 until layer.width || y !in 0 until layer.height
        ) {
            recording.failed = true
            return
        }
        val linearIndex = y * layer.width + x
        recording.cells.getOrPut(layer) { LinkedHashSet() }.add(linearIndex)
    }

    @Synchronized
    fun snapshotFor(map: NativeMap): TerrainTilerDirtySnapshot? {
        val state = published ?: return null
        if (state.map.get() !== map) return null
        check(!state.failed) { "Terrain editor change tracking failed; the sandbox map cannot be exported safely" }
        val layers = state.layers.map { entry ->
            val layer = checkNotNull(entry.layer.get()) {
                "A terrain-edited layer is unavailable; the sandbox map cannot be exported safely"
            }
            TerrainTilerDirtyLayer(layer, entry.cells)
        }
        return TerrainTilerDirtySnapshot(layers)
    }

    @Synchronized
    private fun publish(recording: Recording) {
        val previous = published?.takeIf { it.map.get() === recording.map }
        val combined = IdentityHashMap<MapLayer, MutableSet<Int>>()
        var failed = recording.failed || previous?.failed == true

        previous?.layers?.forEach { entry ->
            val layer = entry.layer.get()
            if (layer == null) failed = true
            else combined.getOrPut(layer) { LinkedHashSet() }.addAll(entry.cells)
        }
        recording.cells.forEach { (layer, cells) ->
            combined.getOrPut(layer) { LinkedHashSet() }.addAll(cells)
        }

        val weakLayers = combined.map { (layer, cells) ->
            WeakLayerCells(WeakReference(layer), Collections.unmodifiableSet(LinkedHashSet(cells)))
        }
        published = PublishedRecording(WeakReference(recording.map), weakLayers, failed)
    }
}

/** Runs the native unit exporter, then merges only recorded terrain cells before destination creation. */
internal fun writeSandboxMapWithTerrain(
    map: NativeMap,
    source: InputStream,
    output: OutputStream,
) {
    val nativeXml = ByteArrayOutputStream()
    map.writeMapXmlWithUnits(source, nativeXml)
    val nativeBytes = nativeXml.toByteArray()
    val snapshot = TerrainTilerWriteCapture.snapshotFor(map)
    if (snapshot == null || snapshot.layers.isEmpty()) {
        output.write(nativeBytes)
        return
    }

    val runtimeLayers = map.layers.indices
        .map { map.layers[it] as? MapLayer ?: error("Unexpected native map layer") }
        .filterNot { it.name.equals("units", ignoreCase = true) }
    val layerLayouts = runtimeLayers.map { layer ->
        SandboxTmxLayerLayout(layer.name, layer.width, layer.height)
    }
    val runtimeTilesets = map.tileSets.map { value ->
        val tileset = value as? TileSet ?: error("Unexpected native map tileset")
        SandboxTmxTilesetLayout(tileset.firstGid, tileset.name)
    }

    val edits = snapshot.layers.map { dirty ->
        val layer = dirty.layer
        check(layer.map === map && runtimeLayers.any { it === layer }) {
            "A recorded terrain layer no longer belongs to the sandbox map"
        }
        check(layer === map.groundLayer || layer === map.groundDetailsLayer || layer === map.groundDetails2Layer) {
            "TerrainTiler wrote to a non-terrain layer"
        }
        val runtimeIndex = runtimeLayers.indexOfFirst { it === layer }
        check(runtimeIndex >= 0) { "A recorded terrain layer is not in the current map" }
        val currentGids = dirty.cells.associateWith { linearIndex ->
            val x = linearIndex % layer.width
            val y = linearIndex / layer.width
            val tile = layer.getTile(x, y) ?: return@associateWith 0
            val tileset = checkNotNull(tile.tileSet) { "A TerrainTiler tile has no tileset" }
            check(runtimeTilesets.indices.any { map.tileSets[it] === tileset }) {
                "A TerrainTiler tile uses an unregistered tileset"
            }
            val gid = tileset.firstGid.toLong() + tile.tileIndex
            check(gid in 1L..SANDBOX_TMX_GID_MASK.toLong()) { "A TerrainTiler tile has an invalid TMX GID" }
            gid.toInt()
        }
        SandboxTmxLayerEdit(runtimeIndex, layer.name, layer.width, layer.height, currentGids)
    }

    val patched = patchSandboxTmx(
        nativeBytes,
        map.mapWidthInTiles,
        map.mapHeightInTiles,
        layerLayouts,
        runtimeTilesets,
        edits,
    )
    output.write(patched)
}
