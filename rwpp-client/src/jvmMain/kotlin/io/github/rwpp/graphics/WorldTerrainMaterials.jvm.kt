/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapLayerBufferBufferImage
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.gameFramework.render.UniquePaint
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import com.corrodinggames.rts.game.map.Map as NativeMap

/** Final map cache composition, so coastline art and ground details receive the same treatment. */
internal object DesktopWorldTerrainMaterials {
    private var map: NativeMap? = null
    private var mask: BitmapOrTexture? = null
    private var encoded = IntArray(0)
    private var tileSignatures = IntArray(0)
    private var terrainLayers: List<MapLayer> = emptyList()
    private var maskResolution = 1
    private var maskWidth = 0
    private val waterSampler = TerrainWaterTileSampler()
    private val rasterWorkspace = WorldTerrainRasterWorkspace()
    private var program: TerrainShader? = null
    private val paints = arrayOfNulls<UniquePaint>(256)
    private var failed = false
    private var time = 0f
    private val cacheCells = IdentityHashMap<RectF, MapLayerBufferBufferImage>()
    private const val DIRTY_CHUNK_TILES = 16
    private val mutationLock = Any()
    private val dirtyChunks = BitSet()
    private var dirtyChunkColumns = 0
    private var mapFrames = 0L
    private var tileSignatureAudits = 0L
    private var signatureTilesScanned = 0L
    private var terrainMaskBuilds = 0L
    private var mutationTriggeredBuilds = 0L
    private var auditTriggeredBuilds = 0L

    // JavaCanvas transforms vertices on the CPU before they reach GL. Source UVs stay in cache
    // coordinates, so they anchor material positions independently of camera/zoom/cache rebuilds.
    internal val VERTEX_SHADER get() = WorldTerrainShaderSources.VERTEX_SHADER
    internal val FRAGMENT_SHADER get() = WorldTerrainShaderSources.FRAGMENT_SHADER

    fun beginMap(nativeMap: NativeMap, frameDelta: Float) {
        if (!WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()) {
            reset(); return
        }
        val engine = GameEngine.getInstance() ?: return
        if (nativeMap.tileWidth <= 0 || nativeMap.tileHeight <= 0 || nativeMap.mapWidthInTiles !in 1..2048 || nativeMap.mapHeightInTiles !in 1..2048) {
            reset(); return
        }
        if (engine.graphics !is JavaCanvas || !(engine.graphics as JavaCanvas).shadersEnabled || failed) return
        time = advanceWorldLightingTime(time, frameDelta)
        NativeMap.acquireMapLock()
        try {
            if (map !== nativeMap || !terrainDrawLayersMatch(nativeMap)) {
                buildMask(nativeMap, engine)
                return
            }
            mapFrames++
            val texture = mask ?: return
            val w = nativeMap.mapWidthInTiles;
            val h = nativeMap.mapHeightInTiles
            // Include the coast radius so off-screen editor changes cannot leave a stale bank.
            val minX = (floor(engine.cameraRenderX / nativeMap.tileWidth).toInt() - 7).coerceAtLeast(0)
            val minY = (floor(engine.cameraRenderY / nativeMap.tileHeight).toInt() - 7).coerceAtLeast(0)
            val maxX =
                (ceil((engine.cameraRenderX + engine.viewWidth) / nativeMap.tileWidth).toInt() + 7).coerceAtMost(w - 1)
            val maxY =
                (ceil((engine.cameraRenderY + engine.viewHeight) / nativeMap.tileHeight).toInt() + 7).coerceAtMost(h - 1)
            if (consumeVisibleTerrainMutation(minX, minY, maxX, maxY)) {
                mutationTriggeredBuilds++
                buildMask(nativeMap, engine)
                return
            }
            // Native edits call MapLayer.setTile and invalidate immediately. Keep a low-frequency
            // audit for extensions that write the public tileIds array directly.
            if (!terrainSignatureAuditDue(mapFrames)) return
            tileSignatureAudits++
            var terrainChanged = false
            audit@ for (y in minY..maxY) for (x in minX..maxX) {
                signatureTilesScanned++
                val i = y * w + x
                if (tileSignatures[i] != terrainTileSignature(terrainLayers, x, y)) {
                    terrainChanged = true
                    break@audit
                }
            }
            if (terrainChanged) {
                auditTriggeredBuilds++
                buildMask(nativeMap, engine)
                return
            }
        } catch (failure: Exception) {
            failed = true
            mask?.disposeBitmap(); mask = null; map = null
            GameEngine.logWarning("RWPP terrain materials disabled: ${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            NativeMap.releaseMapLock()
        }
    }

    private fun terrainDrawLayers(nativeMap: NativeMap): List<MapLayer> =
        worldTerrainLayers(nativeMap)

    /** Compare cached layer identities without allocating a fresh list every frame. */
    private fun terrainDrawLayersMatch(nativeMap: NativeMap): Boolean {
        var index = 0
        val ground = nativeMap.groundLayer
        if (ground != null) {
            if (terrainLayers.getOrNull(index) !== ground) return false
            index++
        }
        val details = nativeMap.groundDetailsLayer
        if (details != null) {
            if (terrainLayers.getOrNull(index) !== details) return false
            index++
        }
        val details2 = nativeMap.groundDetails2Layer
        if (details2 != null) {
            if (terrainLayers.getOrNull(index) !== details2) return false
            index++
        }
        var layerIndex = 0
        while (layerIndex < nativeMap.layers.size) {
            val layer = nativeMap.layers[layerIndex]
            if (layer is MapLayer && layer.isItemsLayer) {
                if (terrainLayers.getOrNull(index) !== layer) return false
                index++
            }
            layerIndex++
        }
        return index == terrainLayers.size
    }

    /** Called by the native MapLayer.setTile hook for normal map/editor terrain writes. */
    fun markTerrainTileChanged(layer: MapLayer, x: Int, y: Int) {
        val activeMap = map ?: return
        if (layer.map !== activeMap || terrainLayers.none { it === layer }) return
        if (x !in 0 until activeMap.mapWidthInTiles || y !in 0 until activeMap.mapHeightInTiles) return
        val columns = (activeMap.mapWidthInTiles + DIRTY_CHUNK_TILES - 1) / DIRTY_CHUNK_TILES
        synchronized(mutationLock) {
            if (dirtyChunkColumns != columns) {
                dirtyChunks.clear()
                dirtyChunkColumns = columns
            }
            val chunkX = x / DIRTY_CHUNK_TILES
            val chunkY = y / DIRTY_CHUNK_TILES
            dirtyChunks.set(chunkY * dirtyChunkColumns + chunkX)
        }
    }

    /** Mutations outside the visible coast margin stay queued until their chunk can affect view. */
    private fun consumeVisibleTerrainMutation(minX: Int, minY: Int, maxX: Int, maxY: Int): Boolean {
        synchronized(mutationLock) {
            if (dirtyChunks.isEmpty() || dirtyChunkColumns <= 0) return false
            val firstX = minX / DIRTY_CHUNK_TILES
            val lastX = maxX / DIRTY_CHUNK_TILES
            val firstY = minY / DIRTY_CHUNK_TILES
            val lastY = maxY / DIRTY_CHUNK_TILES
            for (y in firstY..lastY) for (x in firstX..lastX) {
                if (dirtyChunks.get(y * dirtyChunkColumns + x)) {
                    // The following whole-map build includes this and all off-screen edits.
                    dirtyChunks.clear()
                    return true
                }
            }
            return false
        }
    }

    private fun tileAt(layer: MapLayer, x: Int, y: Int): MapTile? =
        if (x in 0 until layer.width && y in 0 until layer.height) layer.getTile(x, y) else null

    private fun terrainTileSignature(layers: List<MapLayer>, x: Int, y: Int): Int {
        var signature = 1
        for (layer in layers) {
            val tile = tileAt(layer, x, y)
            signature = 31 * signature + System.identityHashCode(tile)
            if (tile != null) signature =
                31 * signature + tile.tileIndex + (if (tile.water) 1 else 0) + (if (tile.waterBridge) 2 else 0) +
                        (if (tile.cliff) 4 else 0) + (if (tile.lava) 8 else 0)
        }
        return signature
    }

    private fun buildMask(nativeMap: NativeMap, engine: GameEngine) {
        val w = nativeMap.mapWidthInTiles;
        val h = nativeMap.mapHeightInTiles
        if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return
        mask?.disposeBitmap(); mask = null; map = nativeMap; cacheCells.clear()
        terrainLayers = terrainDrawLayers(nativeMap)
        synchronized(mutationLock) {
            // Clear mutations already represented by this snapshot. Any later setTile call leaves
            // a fresh bit for a follow-up rebuild.
            dirtyChunks.clear()
            dirtyChunkColumns = (w + DIRTY_CHUNK_TILES - 1) / DIRTY_CHUNK_TILES
        }
        maskResolution = terrainWaterSampleResolution(w, h)
        maskWidth = w * maskResolution
        val maskHeight = h * maskResolution
        val size = maskWidth * maskHeight
        tileSignatures = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            signatureTilesScanned++
            tileSignatures[y * w + x] = terrainTileSignature(terrainLayers, x, y)
        }
        val sampled = sampleWorldTerrain(terrainLayers, waterSampler, 0, 0, w, h, maskResolution, rasterWorkspace)
        val coverage = sampled.coverage;
        val lava = sampled.lava;
        val cliffs = sampled.cliffs
        val distances = sampled.distances
        val created = createTerrainMaskImage(engine.graphics, maskWidth, maskHeight)
        created.ensurePixelData()
        encoded = IntArray(size)
        for (y in 0 until h) for (x in 0 until w) {
            for (sy in 0 until maskResolution) {
                val row = (y * maskResolution + sy) * maskWidth + x * maskResolution
                for (sx in 0 until maskResolution) {
                    val i = row + sx
                    encoded[i] = terrainMaterialMaskPixel(coverage[i], distances[i], lava[i], cliffs[i])
                    created.setPixel(x * maskResolution + sx, y * maskResolution + sy, encoded[i])
                }
            }
        }
        created.commitPixelData()
        (created.loadedImage as? JavaBitmap)?.slickImage?.setFilter(org.newdawn.slick.Image.FILTER_LINEAR)
        mask = created
        terrainMaskBuilds++
    }

    fun drawCachedTerrain(
        graphics: GraphicsEngine,
        texture: BitmapOrTexture,
        source: Rect,
        target: RectF,
        original: Paint
    ) {
        val engine = GameEngine.getInstance()
        val terrainMask = mask
        if (engine == null || engine.graphics !== graphics || terrainMask == null || failed ||
            !WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()
        ) {
            graphics.drawImage(texture, source, target, original); return
        }
        val renderer = graphics as? JavaCanvas
        if (renderer == null || !renderer.shadersEnabled) {
            graphics.drawImage(texture, source, target, original); return
        }
        val shader = program ?: runCatching { TerrainShader() }.getOrElse {
            failed = true; graphics.drawImage(texture, source, target, original); return
        }.also { program = it }
        if (shader.errorState != 0) {
            failed = true; graphics.drawImage(texture, source, target, original); return
        }
        val maskImage = (terrainMask.loadedImage as? JavaBitmap)?.slickImage
        val sourceImage = (texture.loadedImage as? JavaBitmap)?.slickImage
        val activeMap = map
        if (maskImage == null || sourceImage == null || activeMap == null) {
            graphics.drawImage(texture, source, target, original); return
        }
        val cell = cacheCell(target)
        if (cell == null) {
            graphics.drawImage(texture, source, target, original); return
        }
        val scale = (NativeMap.mapLayerBuffer as NativeMapBufferAccess).rwppBufferScale().coerceAtLeast(0.001f)
        shader.setFloatUniform("u_time", time)
        shader.setFloatUniform("u_cacheScale", scale)
        shader.setFloat2Uniform("u_maskWorldOrigin", 0f, 0f)
        shader.setFloatUniform("u_lightStrength", WorldGraphicsEnhancements.worldLightingStrength())
        shader.setFloatUniform("u_waveStrength", WorldGraphicsEnhancements.waterWaveStrength())
        shader.setFloatUniform("u_wetStrength", WorldGraphicsEnhancements.wetGroundStrength())
        shader.setFloatUniform(
            "u_terrainHighlights",
            if (WorldGraphicsEnhancements.surfaceHighlightsEnabled()) 1f else 0f
        )
        shader.setFloat2Uniform("u_cacheWorldOrigin", cell.pixelX.toFloat(), cell.pixelY.toFloat())
        shader.setFloat2Uniform(
            "u_cacheUvWorldScale", sourceImage.width / (sourceImage.textureWidth * scale),
            sourceImage.height / (sourceImage.textureHeight * scale)
        )
        shader.setFloat2Uniform(
            "u_maskWorldScale",
            maskImage.textureWidth / (activeMap.mapWidthInTiles * activeMap.tileWidth),
            maskImage.textureHeight / (activeMap.mapHeightInTiles * activeMap.tileHeight)
        )
        shader.setFloat2Uniform("u_maskExtent", maskImage.textureWidth, maskImage.textureHeight)
        shader.setFloat2Uniform(
            "u_maskTexel",
            maskImage.textureWidth / terrainMask.width,
            maskImage.textureHeight / terrainMask.height
        )
        shader.setFloat2Uniform(
            "u_sourceTexel",
            sourceImage.textureWidth / sourceImage.width,
            sourceImage.textureHeight / sourceImage.height
        )
        shader.setTextureUniform("u_terrainMask", terrainMask)
        // A different shader may have used native texture unit 1 since the previous cache block.
        shader.getUniform("u_terrainMask").dirty = true
        val alpha = original.alpha.coerceIn(0, 255)
        val paint = paints[alpha] ?: UniquePaint().apply {
            color = (alpha shl 24) or 0xffffff; setAntiAlias(true); setShader(shader)
        }.also { paints[alpha] = it }
        withTextureAtlasTemporarilyDisabled({ renderer.textureAtlas }, { renderer.textureAtlas = it }) {
            graphics.drawImage(texture, source, target, paint)
        }
    }

    internal fun materialMask(): BitmapOrTexture? = mask

    internal fun configureMaterialBackground(shader: PaintGLShader, engine: GameEngine, width: Int, height: Int) {
        shader.setFloat2Uniform("u_maskWorldOrigin", 0f, 0f)
        val texture = mask ?: return
        val image = (texture.loadedImage as? JavaBitmap)?.slickImage ?: return
        val nativeMap = map ?: return
        shader.setFloat2Uniform("u_worldOrigin", engine.cameraRenderX, engine.cameraRenderY)
        shader.setFloat2Uniform("u_viewWorldSize", width / engine.zoomScale, height / engine.zoomScale)
        shader.setFloat2Uniform(
            "u_maskWorldScale",
            image.textureWidth / (nativeMap.mapWidthInTiles * nativeMap.tileWidth),
            image.textureHeight / (nativeMap.mapHeightInTiles * nativeMap.tileHeight)
        )
        shader.setFloat2Uniform("u_maskExtent", image.textureWidth, image.textureHeight)
        shader.setFloat2Uniform("u_maskTexel", image.textureWidth / texture.width, image.textureHeight / texture.height)
    }

    internal fun bindSceneMaterials(shader: PaintGLShader, engine: GameEngine, scene: BitmapOrTexture?) {
        shader.setFloatUniform("u_separateSpriteCoverage", 0f)
        shader.setFloatUniform("u_materialPass", 0f)
        if (!WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled()) return
        val material = DesktopWorldUnitMaterials.texture() ?: run {
            if (scene != null) shader.setTextureUniform("u_sceneMaterials", scene)
            return
        }
        val materialImage = (material.loadedImage as? JavaBitmap)?.slickImage ?: return
        val sceneImage = (scene?.loadedImage as? JavaBitmap)?.slickImage ?: return
        val sceneWidth = sceneImage.width / sceneImage.textureWidth
        val sceneHeight = sceneImage.height / sceneImage.textureHeight
        shader.setFloatUniform("u_materialPass", 1f)
        shader.setFloatUniform("u_postSSR", if (WorldGraphicsEnhancements.isScreenSpaceReflectionsEnabled()) 1f else 0f)
        shader.setFloatUniform(
            "u_postTraceSteps", if (WorldGraphicsEnhancements.isScreenSpaceRayTracingEnabled())
                WorldGraphicsEnhancements.screenSpaceRayTraceSteps().toFloat() else 2f
        )
        shader.setFloatUniform(
            "u_surfaceHighlights",
            if (WorldGraphicsEnhancements.surfaceHighlightsEnabled()) 1f else 0f
        )
        shader.setFloatUniform("u_dynamicBloom", if (WorldGraphicsEnhancements.dynamicBloomEnabled()) 1f else 0f)
        shader.setFloatUniform("u_wetStrength", WorldGraphicsEnhancements.wetGroundStrength())
        shader.setFloat2Uniform("u_worldPerUv", sceneWidth / engine.zoomScale, sceneHeight / engine.zoomScale)
        shader.setFloat2Uniform("u_sceneExtent", engine.screenWidth / sceneWidth, engine.screenHeight / sceneHeight)
        shader.setFloat2Uniform(
            "u_materialUvScale",
            sceneWidth / (materialImage.width / materialImage.textureWidth),
            sceneHeight / (materialImage.height / materialImage.textureHeight)
        )
        shader.setTextureUniform("u_sceneMaterials", material)
        shader.getUniform("u_sceneMaterials").dirty = true
    }

    private fun cacheCell(target: RectF): MapLayerBufferBufferImage? {
        cacheCells[target]?.let { return it }
        cacheCells.clear()
        val columns = (NativeMap.mapLayerBuffer as NativeMapBufferAccess).rwppBufferGrid() ?: return null
        for (column in columns) for (value in (column as? Array<*> ?: continue)) {
            val cell = value as? MapLayerBufferBufferImage ?: continue
            cacheCells[cell.destRectF] = cell
        }
        return cacheCells[target]
    }

    private fun reset() {
        rasterWorkspace.clear()
        mask?.disposeBitmap(); mask = null; map = null; encoded = IntArray(0); tileSignatures =
            IntArray(0); terrainLayers = emptyList(); cacheCells.clear()
        synchronized(mutationLock) { dirtyChunks.clear(); dirtyChunkColumns = 0 }
        if (failed) {
            program = null; paints.fill(null)
        }; failed = false
    }

    internal fun performanceStats() = TerrainMaterialPerformanceStats(
        mapFrames, tileSignatureAudits, signatureTilesScanned, terrainMaskBuilds,
        mutationTriggeredBuilds, auditTriggeredBuilds
    )

    private class TerrainShader : PaintGLShader("assets/shaders/post_base.frag") {
        override fun readSources() {
            vertexSource = VERTEX_SHADER; fragmentSource = FRAGMENT_SHADER
        }

        override fun logShader(message: String) {
            // The native uploader logs every sampler rebind; retain diagnostics, avoid frame log spam.
            if (!message.startsWith("Updating texture to:")) super.logShader(message)
        }

        override fun prepareDraw(paint: Paint?, texture: BitmapOrTexture?): Boolean {
            super.prepareDraw(paint, texture); return values.any { it.dirty }
        }
    }
}
