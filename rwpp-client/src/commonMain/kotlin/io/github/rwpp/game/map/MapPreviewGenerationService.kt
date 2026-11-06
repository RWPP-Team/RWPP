/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.game.impl.openGamePath
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.logging.logger
import io.github.rwpp.utils.CacheKeyedImageable
import kotlinx.coroutines.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A generated preview is held only while the caller displays it; no file or native map is created. */
class GeneratedMapPreview internal constructor(
    override val imageCacheKey: String,
    private val png: ByteArray,
) : CacheKeyedImageable {
    override fun openImageInputStream(): InputStream = ByteArrayInputStream(png)
}

/**
 * Builds previews from local orthogonal TMX source and its image assets without loading a game map.
 * Supported layers: fixed-size tile layers with XML, CSV or base64 (plain, gzip or zlib) data;
 * external TSX and image-sheet / image-collection tilesets are supported.
 */
class MapPreviewGenerationService {
    /** Network maps carry a local TMX stream too; generation gives them a preview when no PNG exists. */
    fun supports(map: GameMap): Boolean = map.mapType != GameSetupMapType.savedGame &&
            map.enginePath.endsWith(".tmx", ignoreCase = true)

    suspend fun generate(map: GameMap, maxDimensionPx: Int = DEFAULT_PREVIEW_DIMENSION): Result<GeneratedMapPreview> {
        return try {
            require(supports(map)) { "This map cannot be previewed from a local TMX file" }
            require(maxDimensionPx in MIN_PREVIEW_DIMENSION..MAX_PREVIEW_DIMENSION) {
                "Preview size must be between $MIN_PREVIEW_DIMENSION and $MAX_PREVIEW_DIMENSION pixels"
            }
            Result.success(withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                context.ensureActive()
                val tmx = map.openInputStream().use { it.readBounded(MAX_TMX_BYTES) { context.ensureActive() } }
                val resourceBytes = HashMap<String, ByteArray>()
                val pixels = renderMapPreview(
                    tmxBytes = tmx,
                    mapPath = map.enginePath,
                    maxDimensionPx = maxDimensionPx,
                    readResource = { path ->
                        context.ensureActive()
                        resourceBytes[path] ?: try {
                            openPreviewResource(path)?.use {
                                it.readBounded(MAX_ASSET_BYTES) { context.ensureActive() }
                            }?.also { resourceBytes[path] = it }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    },
                    decodeImage = ::decodeMapPreviewImage,
                )
                context.ensureActive()
                val png = encodeMapPreviewPng(pixels)
                context.ensureActive()
                val hash = MessageDigest.getInstance("SHA-256").digest(png)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                GeneratedMapPreview(
                    imageCacheKey = "generated-map-preview:${map.mapType}:${map.enginePath}:$hash",
                    png = png,
                )
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger().warn("Map preview generation failed for '{}'", map.enginePath, failure)
            Result.failure(failure)
        }
    }
}

/**
 * Tilesets live in three different places: the game asset tree, the extension's
 * layered assets, and the map directory of a modded or downloaded map. The native
 * engine resolves all of them through the abstract file layer, so the preview
 * renderer has to try the same backends instead of assuming built-in assets.
 */
private fun openPreviewResource(path: String): InputStream? {
    val candidates = listOf(path, path.removePrefix("assets/"))
    for (candidate in candidates) {
        runCatching { openGamePath(candidate) }.getOrNull()?.let { return it }
    }
    return runCatching { ApiRuntime.extensionContext.gameAssets().open(path) }.getOrNull()
}

internal data class MapPreviewRaster(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
    }
}

internal expect fun decodeMapPreviewImage(bytes: ByteArray): MapPreviewRaster
internal expect fun encodeMapPreviewPng(image: MapPreviewRaster): ByteArray

private const val DEFAULT_PREVIEW_DIMENSION = 512
private const val MIN_PREVIEW_DIMENSION = 64
private const val MAX_PREVIEW_DIMENSION = 1024
// Large community maps ship their tile data inline; the biggest observed map is 14 MB.
private const val MAX_TMX_BYTES = 32 * 1024 * 1024
private const val MAX_TSX_BYTES = 2 * 1024 * 1024
private const val MAX_ASSET_BYTES = 16 * 1024 * 1024
private const val MAX_CELLS = 4_000_000
private const val MAX_TILES = 128
private const val MAX_LAYERS = 256
private const val MAX_TILE_IMAGES = 4096
private const val MAX_IMAGE_DIMENSION = 8192
private const val MAX_IMAGE_PIXELS = 8_000_000L
private const val MAX_TOTAL_IMAGE_PIXELS = 16_000_000L
private const val MAX_TOTAL_LAYER_CELLS = 16_000_000L
private const val FLAG_H = 0x80000000.toInt()
private const val FLAG_V = 0x40000000
private const val FLAG_D = 0x20000000

private data class PreviewImage(
    val raster: MapPreviewRaster,
    val transparentColor: Int? = null,
)

private class PreviewImageCache {
    val images = HashMap<String, PreviewImage>()
    var pixelCount = 0L
}

private data class PreviewTile(
    val firstGid: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val columns: Int,
    val margin: Int,
    val spacing: Int,
    val tileOffsetX: Int,
    val tileOffsetY: Int,
    val sheet: PreviewImage?,
    val perTileImages: Map<Int, PreviewImage>,
    val tileCount: Int,
)

private data class PreviewLayer(
    val element: Element,
    val offsetX: Double,
    val offsetY: Double,
    val opacity: Double,
)

/** Pure renderer kept separate from FileLoader, Coil, and the native game engine for bounded tests. */
internal suspend fun renderMapPreview(
    tmxBytes: ByteArray,
    mapPath: String,
    maxDimensionPx: Int,
    readResource: (String) -> ByteArray?,
    decodeImage: (ByteArray) -> MapPreviewRaster,
): MapPreviewRaster {
    val coroutineContext = currentCoroutineContext()
    coroutineContext.ensureActive()
    require(tmxBytes.size <= MAX_TMX_BYTES) { "TMX file is too large" }
    val document = parsePreviewXml(tmxBytes)
    val root = document.documentElement
    require(root.tagName == "map") { "The TMX document has no map root" }
    require(root.getAttribute("orientation") == "orthogonal") {
        "Only orthogonal TMX maps are supported"
    }
    require(root.getAttribute("infinite") != "1") { "Infinite TMX maps are not supported" }
    val mapWidth = root.intAttr("width")
    val mapHeight = root.intAttr("height")
    val tileWidth = root.intAttr("tilewidth")
    val tileHeight = root.intAttr("tileheight")
    val cellCount = mapWidth.toLong() * mapHeight
    require(mapWidth > 0 && mapHeight > 0 && cellCount <= MAX_CELLS) { "TMX map dimensions exceed the preview limit" }
    require(tileWidth in 1..256 && tileHeight in 1..256) { "Invalid TMX tile dimensions" }
    require(maxDimensionPx in MIN_PREVIEW_DIMENSION..MAX_PREVIEW_DIMENSION)

    var totalAssetBytes = 0L
    val seenAssetBytes = HashMap<String, Int>()
    // Community maps routinely reference tilesets and images the author never shipped.
    // The engine loads such a map and draws those tiles as empty, so the preview keeps
    // the GID range and leaves it blank instead of dropping the whole image.
    fun loadResource(path: String, baseDirectory: String): Pair<String, ByteArray>? {
        val candidates = previewResourceCandidates(path, baseDirectory, mapPath)
        for (candidate in candidates) {
            val bytes = readResource(candidate) ?: continue
            if (seenAssetBytes.putIfAbsent(candidate, bytes.size) == null) totalAssetBytes += bytes.size
            require(bytes.size <= MAX_ASSET_BYTES && totalAssetBytes <= MAX_ASSET_BYTES * 4L) {
                "TMX referenced assets exceed the preview size limit"
            }
            return candidate to bytes
        }
        return null
    }

    val mapDirectory = mapPath.substringBeforeLast('/', "")
    val decodedImages = PreviewImageCache()
    val tilesets = root.directChildren("tileset").mapNotNull { element ->
        val firstGid = element.intAttr("firstgid")
        require(firstGid > 0) { "Tileset firstgid must be positive" }
        val source = element.getAttribute("source").takeIf(String::isNotBlank)
        if (source == null) {
            // Inline Rusted Warfare tilesets use paths rooted at the game tilesets directory.
            parsePreviewTileset(firstGid, element, "tilesets", ::loadResource, decodeImage, decodedImages)
        } else {
            val external = loadResource(source, mapDirectory) ?: return@mapNotNull emptyPreviewTile(firstGid)
            require(external.second.size <= MAX_TSX_BYTES) { "External TSX file is too large" }
            val tilesetElement = parsePreviewXml(external.second).documentElement
            require(tilesetElement.tagName == "tileset") { "External TSX has no tileset root" }
            parsePreviewTileset(
                firstGid, tilesetElement, external.first.substringBeforeLast('/', ""),
                ::loadResource, decodeImage, decodedImages,
            )
        }
    }.sortedBy { it.firstGid }
    require(tilesets.isNotEmpty()) { "TMX map contains no tilesets" }
    require(tilesets.size <= MAX_TILES) { "TMX map contains too many tilesets" }
    require(tilesets.first().firstGid >= 1) { "The first TMX tileset must have a positive GID" }
    require(tilesets.zipWithNext().all { (a, b) -> a.firstGid < b.firstGid }) { "TMX tileset GIDs overlap" }

    val logicalWidth = mapWidth.toDouble() * tileWidth
    val logicalHeight = mapHeight.toDouble() * tileHeight
    val scale = min(1.0, maxDimensionPx / max(logicalWidth, logicalHeight))
    val outputWidth = ceil(logicalWidth * scale).toInt().coerceAtLeast(1)
    val outputHeight = ceil(logicalHeight * scale).toInt().coerceAtLeast(1)
    val background = root.getAttribute("backgroundcolor").takeIf(String::isNotBlank)?.let(::parseTmxColor)
        ?: 0xFF111923.toInt()
    val target = IntArray(outputWidth * outputHeight) { background }
    val layers = ArrayList<PreviewLayer>()
    collectPreviewLayers(root, 0.0, 0.0, 1.0, layers)
    var totalLayerCells = 0L
    layers.forEach { layer ->
        val width = layer.element.getAttribute("width").toIntOrNull() ?: mapWidth
        val height = layer.element.getAttribute("height").toIntOrNull() ?: mapHeight
        // Community maps sometimes give a layer its own dimensions. Drawing clips to
        // the preview raster, so only the work bound has to be enforced here.
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_CELLS) {
            "TMX tile layer dimensions exceed the preview limit"
        }
        totalLayerCells += width.toLong() * height
        require(totalLayerCells <= MAX_TOTAL_LAYER_CELLS) { "TMX layers exceed the preview work limit" }
        val data = layer.element.directChildren("data").singleOrNull()
            ?: error("TMX tile layer is missing its data element")
        require(data.directChildren("chunk").isEmpty()) { "Infinite TMX layer chunks are unsupported" }
        val gids = decodeGids(data, width, height) { coroutineContext.ensureActive() }
        val layerX = layer.offsetX + layer.element.optionalFiniteDouble("x", 0.0) * tileWidth
        val layerY = layer.offsetY + layer.element.optionalFiniteDouble("y", 0.0) * tileHeight
        for (y in 0 until height) {
            if (y and 7 == 0) coroutineContext.ensureActive()
            for (x in 0 until width) {
                if (x and 1023 == 0) coroutineContext.ensureActive()
                val raw = gids[y * width + x]
                val gid = raw and 0x0FFFFFFF
                if (gid == 0) continue
                val tileset = tilesets.findTileset(gid) ?: continue
                val localId = gid - tileset.firstGid
                val perTile = tileset.perTileImages[localId]
                val image = perTile ?: tileset.sheet ?: continue
                val src = if (perTile == null) tileset.sheetRect(localId, image.raster) else TileRect(
                    0, 0, image.raster.width, image.raster.height
                )
                if (src == null) continue
                val tileX = layerX + x * tileWidth + tileset.tileOffsetX
                val tileY = layerY + y * tileHeight + tileHeight - src.height + tileset.tileOffsetY
                drawTile(
                    target, outputWidth, outputHeight, scale, tileX, tileY, src,
                    image, layer.opacity, (raw and FLAG_H) != 0, (raw and FLAG_V) != 0, (raw and FLAG_D) != 0,
                    checkActive = { coroutineContext.ensureActive() },
                )
            }
        }
    }
    return MapPreviewRaster(outputWidth, outputHeight, target)
}

private data class TileRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Native TileSet.getEmbeddedPngProperty reads tileset-level properties for an
 * embedded_png value or text node. The base64 blob is the actual tileset sheet,
 * while the image source often names a file that was never shipped with the map.
 */
private fun embeddedPngBytes(root: Element): ByteArray? {
    val directBlocks = root.directChildren("properties")
    // Native TileSet reads the first descendant properties block. Prefer the
    // tileset-level one so a per-tile property cannot replace the whole sheet.
    val blocks = directBlocks.ifEmpty {
        val all = root.getElementsByTagName("properties")
        listOfNotNull(all.item(0) as? Element)
    }
    for (block in blocks) {
        val properties = block.getElementsByTagName("property")
        for (index in 0 until properties.length) {
            val property = properties.item(index) as? Element ?: continue
            if (property.getAttribute("name") != "embedded_png") continue
            val inline = property.textContent?.trim().orEmpty()
            val raw = property.getAttribute("value").takeIf(String::isNotBlank)
                ?: inline.takeIf(String::isNotBlank)
                ?: continue
            val normalized = raw.filterNot(Char::isWhitespace)
            val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
            val bytes = runCatching { decodeBase64(padded) }.getOrNull() ?: continue
            if (bytes.isNotEmpty() && bytes.size <= MAX_ASSET_BYTES) return bytes
        }
    }
    return null
}

/** Claims a GID range whose tileset is missing, so the tiles inside it draw nothing. */
private fun emptyPreviewTile(firstGid: Int) = PreviewTile(
    firstGid = firstGid,
    tileWidth = 1,
    tileHeight = 1,
    columns = 0,
    margin = 0,
    spacing = 0,
    tileOffsetX = 0,
    tileOffsetY = 0,
    sheet = null,
    perTileImages = emptyMap(),
    tileCount = 0,
)

private fun List<PreviewTile>.findTileset(gid: Int): PreviewTile? {
    var low = 0
    var high = lastIndex
    var match = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle].firstGid <= gid) {
            match = middle
            low = middle + 1
        } else high = middle - 1
    }
    return getOrNull(match)
}

private fun PreviewTile.sheetRect(localId: Int, image: MapPreviewRaster): TileRect? {
    if (columns <= 0 || tileWidth <= 0 || tileHeight <= 0 || localId < 0 || localId >= tileCount) return null
    val column = localId % columns
    val row = localId / columns
    val x = margin + column * (tileWidth + spacing)
    val y = margin + row * (tileHeight + spacing)
    if (x + tileWidth > image.width || y + tileHeight > image.height) return null
    return TileRect(x, y, tileWidth, tileHeight)
}

private fun parsePreviewTileset(
    firstGid: Int,
    root: Element,
    baseDirectory: String,
    loadResource: (String, String) -> Pair<String, ByteArray>?,
    decodeImage: (ByteArray) -> MapPreviewRaster,
    decodedImages: PreviewImageCache,
): PreviewTile {
    val tileWidth = root.intAttr("tilewidth")
    val tileHeight = root.intAttr("tileheight")
    require(tileWidth in 1..512 && tileHeight in 1..512) { "Invalid tileset dimensions" }
    val margin = root.getAttribute("margin").toIntOrNull() ?: 0
    val spacing = root.getAttribute("spacing").toIntOrNull() ?: 0
    require(margin >= 0 && spacing >= 0) { "Invalid tileset spacing" }
    val offset = root.directChildren("tileoffset").firstOrNull()
    val tileOffsetX = offset?.getAttribute("x")?.toIntOrNull() ?: 0
    val tileOffsetY = offset?.getAttribute("y")?.toIntOrNull() ?: 0
    fun decodeResource(bytes: ByteArray, cacheKey: String): PreviewImage? {
        val decoded = decodedImages.images[cacheKey] ?: run {
            // A tileset image that is present but unreadable is treated like a missing one.
            val raster = runCatching { decodeImage(bytes) }.getOrNull() ?: return null
            require(
                raster.width <= MAX_IMAGE_DIMENSION && raster.height <= MAX_IMAGE_DIMENSION &&
                        raster.width.toLong() * raster.height <= MAX_IMAGE_PIXELS
            ) { "Tileset image dimensions exceed the preview limit" }
            decodedImages.pixelCount += raster.width.toLong() * raster.height
            require(decodedImages.pixelCount <= MAX_TOTAL_IMAGE_PIXELS) {
                "TMX tilesets exceed the total decoded image limit"
            }
            PreviewImage(raster).also { decodedImages.images[cacheKey] = it }
        }
        return decoded
    }

    fun readImage(imageElement: Element?): PreviewImage? {
        imageElement ?: return null
        val source = imageElement.getAttribute("source").takeIf(String::isNotBlank) ?: return null
        val keyColor = imageElement.getAttribute("trans").takeIf(String::isNotBlank)?.let(::parseRgbKey)
        val (path, bytes) = loadResource(source, baseDirectory) ?: return null
        return decodeResource(bytes, path)?.copy(transparentColor = keyColor)
    }

    val imageElement = root.directChildren("image").firstOrNull()
    val keyColor = imageElement?.getAttribute("trans")?.takeIf(String::isNotBlank)?.let(::parseRgbKey)
    // Rusted Warfare stores the tileset sheet in the tileset's embedded_png property.
    // Native TileSet decodes that base64 blob and never opens the external image
    // source, so resolving only files leaves most community maps with blank ground.
    val embedded = embeddedPngBytes(root)
    val sheet = if (embedded != null) {
        decodeResource(embedded, "[embedded]$firstGid")?.copy(transparentColor = keyColor)
    } else {
        readImage(imageElement)
    }
    val tileElements = root.directChildren("tile")
    require(tileElements.size <= MAX_TILE_IMAGES) { "Tileset has too many individual tile images" }
    val tileImages = tileElements.mapNotNull { tile ->
        val id = tile.getAttribute("id").toIntOrNull() ?: return@mapNotNull null
        val image = readImage(tile.directChildren("image").firstOrNull()) ?: return@mapNotNull null
        id to image
    }.toMap()
    if (sheet == null && tileImages.isEmpty()) {
        return emptyPreviewTile(firstGid).copy(tileWidth = tileWidth, tileHeight = tileHeight)
    }
    val columns = root.getAttribute("columns").toIntOrNull()?.takeIf { it > 0 } ?: sheet?.let {
        ((it.raster.width - margin * 2 + spacing) / (tileWidth + spacing)).coerceAtLeast(0)
    } ?: 0
    val count = root.getAttribute("tilecount").toIntOrNull()?.takeIf { it > 0 } ?: if (columns > 0 && sheet != null) {
        columns * ((sheet.raster.height - margin * 2 + spacing) / (tileHeight + spacing)).coerceAtLeast(0)
    } else (tileImages.keys.maxOrNull()?.plus(1) ?: 0)
    require(count <= 65_536) { "Tileset tile count exceeds the preview limit" }
    return PreviewTile(
        firstGid, tileWidth, tileHeight, columns, margin, spacing,
        tileOffsetX, tileOffsetY, sheet, tileImages, count,
    )
}

private fun collectPreviewLayers(
    parent: Element,
    parentX: Double,
    parentY: Double,
    parentOpacity: Double,
    output: MutableList<PreviewLayer>,
    depth: Int = 0,
) {
    require(depth <= 16) { "TMX layer groups are nested too deeply" }
    parent.directElementChildren().forEach { element ->
        val visible = element.getAttribute("visible") != "0"
        if (!visible) return@forEach
        val offsetX = parentX + element.optionalFiniteDouble("offsetx", 0.0)
        val offsetY = parentY + element.optionalFiniteDouble("offsety", 0.0)
        val opacity = parentOpacity * element.optionalFiniteDouble("opacity", 1.0).coerceIn(0.0, 1.0)
        when (element.tagName) {
            "layer" -> {
                output += PreviewLayer(element, offsetX, offsetY, opacity)
                require(output.size <= MAX_LAYERS) { "TMX contains too many tile layers" }
            }

            "group" -> collectPreviewLayers(element, offsetX, offsetY, opacity, output, depth + 1)
        }
    }
}

private fun decodeGids(data: Element, width: Int, height: Int, checkActive: () -> Unit): IntArray {
    val expectedCount = width.toLong() * height
    require(expectedCount in 1..MAX_CELLS.toLong()) { "TMX layer exceeds the preview limit" }
    val encoding = data.getAttribute("encoding")
    val compression = data.getAttribute("compression")
    val text = data.textContent.trim()
    if (encoding != "base64") require(compression.isBlank()) { "Compression requires base64 TMX data" }
    val gids = when (encoding) {
        "base64" -> {
            val compressed = decodeBase64(text)
            val bytes = when (compression) {
                "" -> compressed
                "gzip" -> GZIPInputStream(ByteArrayInputStream(compressed)).use {
                    it.readBounded(expectedCount.toInt() * 4, checkActive)
                }

                "zlib" -> InflaterInputStream(ByteArrayInputStream(compressed)).use {
                    it.readBounded(expectedCount.toInt() * 4, checkActive)
                }

                else -> error("Unsupported TMX compression '$compression'")
            }
            require(bytes.size == expectedCount.toInt() * 4) { "TMX layer data has the wrong byte count" }
            IntArray(expectedCount.toInt()) { index ->
                val offset = index * 4
                (bytes[offset].toInt() and 0xff) or
                        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                        (bytes[offset + 3].toInt() shl 24)
            }
        }

        "csv" -> decodeCsvGids(text, expectedCount.toInt())
        "" -> data.directChildren("tile").map { tile ->
            tile.getAttribute("gid").toLongOrNull()?.let(::uintGidToInt)
                ?: error("TMX XML layer contains an invalid GID")
        }.toIntArray()

        else -> error("Unsupported TMX encoding '$encoding'")
    }
    return if (gids.size == expectedCount.toInt()) gids else gids.copyOf(expectedCount.toInt())
}

private fun decodeCsvGids(text: String, expectedCount: Int): IntArray {
    val output = IntArray(expectedCount)
    val clean = text.trim().trimEnd(',', ' ', '\r', '\n', '\t')
    var start = 0
    var index = 0
    while (start <= clean.length && index < expectedCount) {
        val delimiter = clean.indexOf(',', start)
        val end = if (delimiter < 0) clean.length else delimiter
        val cell = clean.substring(start, end).trim()
        if (cell.isNotEmpty()) {
            // Editors occasionally leave a malformed or extra cell. An empty tile is
            // preferable to dropping the whole preview for one bad number.
            output[index++] = cell.toLongOrNull()?.let(::uintGidToInt) ?: 0
        }
        if (delimiter < 0) break
        start = delimiter + 1
    }
    return output
}

private fun uintGidToInt(value: Long): Int? = value.takeIf { it in 0..0xFFFF_FFFFL }?.toInt()

private val previewBase64Values = IntArray(128) { -1 }.apply {
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { index, char ->
        this[char.code] = index
    }
}

private fun decodeBase64(source: String): ByteArray {
    val text = source.filterNot(Char::isWhitespace)
    require(text.length % 4 == 0) { "Malformed base64 TMX layer data" }
    val padding = when {
        text.endsWith("==") -> 2
        text.endsWith("=") -> 1
        else -> 0
    }
    val output = ByteArray(text.length / 4 * 3 - padding)
    var outputIndex = 0
    for (index in text.indices step 4) {
        val lastGroup = index + 4 == text.length
        val aChar = text[index]
        val bChar = text[index + 1]
        val cChar = text[index + 2]
        val dChar = text[index + 3]
        require(cChar != '=' || dChar == '=') { "Malformed base64 padding" }
        require(lastGroup || (cChar != '=' && dChar != '=')) { "Malformed base64 padding" }
        fun decoded(char: Char): Int = char.code.takeIf { it < previewBase64Values.size }
            ?.let { previewBase64Values[it] } ?: -1

        val a = decoded(aChar)
        val b = decoded(bChar)
        val c = if (cChar == '=') 0 else decoded(cChar)
        val d = if (dChar == '=') 0 else decoded(dChar)
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "Malformed base64 TMX layer data" }
        val bits = (a shl 18) or (b shl 12) or (c shl 6) or d
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 16).toByte()
        if (outputIndex < output.size) output[outputIndex++] = (bits ushr 8).toByte()
        if (outputIndex < output.size) output[outputIndex++] = bits.toByte()
    }
    return output
}

private fun drawTile(
    target: IntArray,
    targetWidth: Int,
    targetHeight: Int,
    scale: Double,
    tileX: Double,
    tileY: Double,
    rect: TileRect,
    image: PreviewImage,
    layerOpacity: Double,
    flipH: Boolean,
    flipV: Boolean,
    flipD: Boolean,
    checkActive: () -> Unit,
) {
    val left = floor(tileX * scale).toInt()
    val top = floor(tileY * scale).toInt()
    val right = ceil((tileX + rect.width) * scale).toInt()
    val bottom = ceil((tileY + rect.height) * scale).toInt()
    val drawnWidth = right - left
    val drawnHeight = bottom - top
    if (drawnWidth <= 0 || drawnHeight <= 0 || layerOpacity <= 0.0) return
    for (targetY in max(0, top) until min(targetHeight, bottom)) {
        checkActive()
        val dy = targetY - top
        val baseV = ((dy + 0.5) / drawnHeight).coerceIn(0.0, 0.999999)
        for (targetX in max(0, left) until min(targetWidth, right)) {
            val dx = targetX - left
            var u = ((dx + 0.5) / drawnWidth).coerceIn(0.0, 0.999999)
            var v = baseV
            if (flipH) u = 1.0 - u
            if (flipV) v = 1.0 - v
            if (flipD) {
                val oldU = u
                u = 1.0 - v
                v = 1.0 - oldU
            }
            val srcX = rect.x + (u * rect.width).toInt().coerceIn(0, rect.width - 1)
            val srcY = rect.y + (v * rect.height).toInt().coerceIn(0, rect.height - 1)
            var source = image.raster.pixels[srcY * image.raster.width + srcX]
            if (image.transparentColor != null && (source and 0x00FFFFFF) == image.transparentColor) continue
            if (layerOpacity < 1.0) {
                val alpha = ((source ushr 24) * layerOpacity).toInt().coerceIn(0, 255)
                source = (source and 0x00FFFFFF) or (alpha shl 24)
            }
            val index = targetY * targetWidth + targetX
            target[index] = alphaOver(source, target[index])
        }
    }
}

private fun alphaOver(source: Int, destination: Int): Int {
    val sourceAlpha = source ushr 24
    if (sourceAlpha == 255) return source
    if (sourceAlpha == 0) return destination
    val destAlpha = destination ushr 24
    val outAlphaNumerator = sourceAlpha * 255 + destAlpha * (255 - sourceAlpha)
    if (outAlphaNumerator == 0) return 0
    fun channel(shift: Int): Int {
        val src = source ushr shift and 0xff
        val dst = destination ushr shift and 0xff
        return ((src * sourceAlpha * 255 + dst * destAlpha * (255 - sourceAlpha)) / outAlphaNumerator)
            .coerceIn(0, 255)
    }

    val outAlpha = outAlphaNumerator / 255
    return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun parsePreviewXml(bytes: ByteArray): Document {
    require(bytes.size <= MAX_TMX_BYTES) { "TMX / TSX XML is too large" }
    // Android's XML provider does not implement every desktop JAXP feature. Reject declarations
    // before parsing instead of making ordinary UTF-8 maps depend on those optional switches.
    val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) bytes.copyOfRange(3, bytes.size) else bytes
    val xml = decodePreviewXml(body)
    require('\u0000' !in xml) { "Preview XML contains a NUL byte" }
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) {
        "DTD and entity declarations are not supported in preview XML"
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isExpandEntityReferences = false
        runCatching { isXIncludeAware = false }
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    // The text is already decoded, so hand the parser characters instead of bytes. Parsing
    // bytes would make it decode again with the declaration's charset, and rewriting the
    // declaration would also hit element attributes such as the layer's own encoding.
    return builder.parse(InputSource(StringReader(xml)))
}

/** Community maps are often saved with a BOM or a local code page instead of UTF-8. */
private fun decodePreviewXml(bytes: ByteArray): String {
    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
        .onSuccess { return it }
    val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
    val declared = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .find(head)?.groupValues?.getOrNull(1)
    val charset = declared?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
        ?: Charsets.ISO_8859_1
    return String(bytes, charset).removePrefix("\uFEFF")
}

internal fun previewResourceCandidates(source: String, baseDirectory: String, mapPath: String): List<String> {
    require(source.isNotBlank() && !source.contains("://") && !source.startsWith("data:", ignoreCase = true)) {
        "Remote and embedded TMX assets are unsupported"
    }
    val mapDirectory = mapPath.replace('\\', '/').substringBeforeLast('/', "")
    val candidates = linkedSetOf<String>()
    fun add(candidate: String) {
        normalizeResourcePath(candidate)?.takeIf(::isAllowedResourcePath)?.let(candidates::add)
    }
    val fileName = source.replace('\\', '/').substringAfterLast('/')
    val bareSource = source.replace('\\', '/').removePrefix("assets/")
    if (source.startsWith('/')) {
        add(source)
        // Author-machine absolute paths appear in shipped and community maps. The
        // game keeps those tilesets in its own shared directories.
        add("tilesets/$fileName")
        add("tilesets/bitmaps/$fileName")
    } else {
        add("$baseDirectory/$bareSource")
        add("tilesets/$bareSource")
        add("$mapDirectory/$bareSource")
        add(bareSource)
        if (source.endsWith(".tsx", ignoreCase = true)) {
            // Shipped maps retain author-machine TSX paths; native loading also
            // resolves their filename from the shared tilesets root.
            add("tilesets/$fileName")
            add("tilesets/terrain/$fileName")
            add("tilesets/ridges/$fileName")
        }
        // Native maps also contain legacy image paths such as terrain/bitmaps/foo.png or
        // ../rusted_warfare/assets/tilesets/bitmaps/foo.png; the engine resolves their
        // filename from the shared tilesets directories. Only the filename is reused, so
        // a traversal segment cannot escape the allowed roots.
        if (source.endsWith(".png", ignoreCase = true)) {
            add("tilesets/bitmaps/$fileName")
            add("tilesets/$fileName")
        }
    }
    return candidates.toList()
}

private val PREVIEW_RESOURCE_EXTENSIONS =
    listOf(".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp", ".tsx", ".tmx")
private val PREVIEW_RESOURCE_ROOTS = listOf(
    "tilesets/", "maps/", "mods/", "saves/", "assets/", "terrain/", "bitmaps/", "ridges/",
    "rustedwarfare/", "rusted_warfare_maps/",
)

/** Traversal is rejected by normalization; the root check also blocks paths that escaped it. */
private fun isAllowedResourcePath(path: String): Boolean {
    val normalized = path.lowercase()
    if (normalized.startsWith("/")) return normalized.startsWith("/sd/")
    return PREVIEW_RESOURCE_EXTENSIONS.any { normalized.endsWith(it) } &&
            PREVIEW_RESOURCE_ROOTS.any { normalized.startsWith(it) }
}

private fun normalizeResourcePath(path: String): String? {
    val normalized = path.replace('\\', '/').removePrefix("./")
    if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    val absolute = normalized.startsWith('/')
    val stack = ArrayList<String>()
    normalized.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
            else -> stack += part
        }
    }
    val result = (if (absolute) "/" else "") + stack.joinToString("/")
    val lower = result.lowercase()
    if (absolute && !lower.startsWith("/sd/")) return null
    return result.takeIf { it.isNotBlank() }
}

private fun parseTmxColor(value: String): Int {
    val raw = value.removePrefix("#")
    return when (raw.length) {
        6 -> (0xFF000000L or raw.toLong(16)).toInt()
        8 -> raw.toLong(16).toInt()
        else -> error("Invalid TMX background color")
    }
}

private fun parseRgbKey(value: String): Int {
    val raw = value.removePrefix("#")
    require(raw.length == 6) { "Invalid TMX transparent color" }
    return raw.toInt(16) and 0x00FFFFFF
}

private fun Element.intAttr(name: String): Int = getAttribute(name).toIntOrNull()
    ?: error("TMX element <$tagName> has an invalid '$name' attribute")

private fun Element.optionalFiniteDouble(name: String, default: Double): Double {
    val value = getAttribute(name).takeIf(String::isNotBlank) ?: return default
    return value.toDoubleOrNull()?.takeIf(Double::isFinite)
        ?: error("TMX element <$tagName> has an invalid '$name' attribute")
}

private fun Element.directChildren(name: String): List<Element> = directElementChildren().filter { it.tagName == name }

private fun Element.directElementChildren(): List<Element> {
    val elements = ArrayList<Element>()
    var child: Node? = firstChild
    while (child != null) {
        if (child is Element) elements += child
        child = child.nextSibling
    }
    return elements
}

private fun InputStream.readBounded(limit: Int, checkActive: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream(min(limit, 64 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        checkActive()
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "File exceeds the preview size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

