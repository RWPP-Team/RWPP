/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal const val SANDBOX_TMX_GID_MASK = 0x1fffffff

private val nativeBase64Values = IntArray(128) { -1 }.apply {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    alphabet.forEachIndexed { index, character -> this[character.code] = index }
}

internal data class SandboxTmxLayerLayout(
    val name: String,
    val width: Int,
    val height: Int,
)

internal data class SandboxTmxTilesetLayout(
    val firstGid: Int,
    val source: String?,
)

internal data class SandboxTmxLayerEdit(
    /** Index in the runtime layer list after native-only `units` layers are removed. */
    val runtimeLayerIndex: Int,
    val name: String,
    val width: Int,
    val height: Int,
    /** Row-major index to native GID. Values are only supplied for cells written by TerrainTiler. */
    val cellGids: Map<Int, Int>,
)

private val nativeTerrainTilesetSources = setOf(
    "terrain/Long Grass.tsx",
    "terrain/Water.tsx",
    "terrain/Sand.tsx",
    "ridges/Sand Nothing - Flat.tsx",
    "terrain/Dust.tsx",
    "ridges/Nothing Dust - Flat.tsx",
)

/**
 * Patches only TerrainTiler-recorded cells into the native unit export.
 * Every untouched layer/data node is left in the parsed document unchanged.
 */
internal fun patchSandboxTmx(
    input: ByteArray,
    mapWidth: Int,
    mapHeight: Int,
    runtimeLayers: List<SandboxTmxLayerLayout>,
    runtimeTilesets: List<SandboxTmxTilesetLayout>,
    edits: List<SandboxTmxLayerEdit>,
): ByteArray {
    require(mapWidth > 0 && mapHeight > 0 && mapWidth.toLong() * mapHeight <= Int.MAX_VALUE / 4) {
        "Invalid native map dimensions"
    }
    val document = parseTmx(input)
    val root = document.documentElement
    check(root.tagName == "map" && root.getAttribute("orientation") == "orthogonal") {
        "Only orthogonal TMX maps can be edited safely"
    }
    check(root.requiredInt("width") == mapWidth && root.requiredInt("height") == mapHeight) {
        "The sandbox dimensions differ from the source TMX"
    }

    val allLayerElements = root.directChildren("layer")
    check(allLayerElements.size == root.getElementsByTagName("layer").length) {
        "Grouped or nested TMX layers are unsupported for terrain export"
    }
    val sourceLoadedLayers = allLayerElements.filterNot { sourceLayer ->
        val name = sourceLayer.getAttribute("name")
        name.equals("set", true) || name.equals("set-disabled", true) || name.equals("units", true)
    }
    check(runtimeLayers.size >= sourceLoadedLayers.size) {
        "The live map is missing layers from the source TMX"
    }
    sourceLoadedLayers.forEachIndexed { index, element ->
        val live = runtimeLayers[index]
        check(live.name.equals(element.getAttribute("name"), ignoreCase = true)) {
            "The live layer order differs from the source TMX"
        }
        check(live.width == element.requiredInt("width") && live.height == element.requiredInt("height")) {
            "A source layer changed dimensions during terrain editing"
        }
    }

    val newRuntimeLayers = runtimeLayers.drop(sourceLoadedLayers.size)
    val acceptedNewNames = setOf("grounddetails", "grounddetails2")
    val seenNewNames = LinkedHashSet<String>()
    newRuntimeLayers.forEach { layer ->
        val name = layer.name.lowercase()
        check(name in acceptedNewNames && seenNewNames.add(name)) {
            "Unexpected runtime layer '${layer.name}' cannot be exported safely"
        }
        check(layer.width == mapWidth && layer.height == mapHeight) {
            "A terrain detail layer does not match map dimensions"
        }
    }

    val allTilesetElements = root.directChildren("tileset")
    check(allTilesetElements.size == root.getElementsByTagName("tileset").length) {
        "Nested TMX tilesets are unsupported for terrain export"
    }
    check(runtimeTilesets.size >= allTilesetElements.size) {
        "The live map is missing tilesets from the source TMX"
    }
    allTilesetElements.forEachIndexed { index, element ->
        val live = runtimeTilesets[index]
        check(live.firstGid == element.requiredInt("firstgid")) {
            "The live tileset GID layout differs from the source TMX"
        }
        val source = element.getAttribute("source").takeIf(String::isNotEmpty)
        check(live.source == source) { "The live tileset source order differs from the source TMX" }
    }
    for (index in 1 until runtimeTilesets.size) {
        check(runtimeTilesets[index - 1].firstGid < runtimeTilesets[index].firstGid) {
            "Runtime tileset GIDs are not strictly increasing"
        }
    }

    val seenEditLayers = LinkedHashSet<Int>()
    val existingLayerUpdates = ArrayList<Pair<Element, IntArray>>()
    val newLayerUpdates = LinkedHashMap<Int, IntArray>()
    val expectedPatchedLayers = LinkedHashMap<Int, IntArray>()
    val runtimeNewTilesetIndices = (allTilesetElements.size until runtimeTilesets.size).toList()
    runtimeNewTilesetIndices.forEach { index ->
        val source = checkNotNull(runtimeTilesets[index].source) {
            "A new terrain tileset has no external source"
        }
        check(
            source in nativeTerrainTilesetSources &&
                    !source.startsWith('/') && !source.contains('\\') &&
                    source.split('/').none { it == ".." || it.isEmpty() }
        ) { "Unsupported terrain tileset resource path: $source" }
    }

    edits.forEach { edit ->
        check(seenEditLayers.add(edit.runtimeLayerIndex)) { "Duplicate terrain patch for a layer" }
        check(edit.runtimeLayerIndex in runtimeLayers.indices) { "Terrain edit refers to an unknown layer" }
        val liveLayer = runtimeLayers[edit.runtimeLayerIndex]
        check(
            liveLayer.name.equals(edit.name, ignoreCase = true) &&
                    liveLayer.width == edit.width && liveLayer.height == edit.height
        ) { "Terrain edit metadata does not match the live layer" }
        check(edit.width > 0 && edit.height > 0 && edit.width.toLong() * edit.height <= Int.MAX_VALUE) {
            "Terrain layer dimensions are invalid"
        }
        val cellCount = edit.width * edit.height
        check(edit.cellGids.keys.all { it in 0 until cellCount }) {
            "Terrain edit contains an out-of-bounds cell"
        }
        check(edit.cellGids.values.all { it in 0..SANDBOX_TMX_GID_MASK }) {
            "Terrain edit contains an invalid TMX GID"
        }
        check(edit.cellGids.values.all { gid ->
            gid == 0 || runtimeTilesets.indices.any { tileSetIndex ->
                gid in runtimeTilesets[tileSetIndex].firstGid until
                        (runtimeTilesets.getOrNull(tileSetIndex + 1)?.firstGid ?: (SANDBOX_TMX_GID_MASK + 1))
            }
        }) { "A terrain GID is not covered by any live tileset" }

        val sourceIndex = edit.runtimeLayerIndex
        if (sourceIndex < sourceLoadedLayers.size) {
            val sourceLayer = sourceLoadedLayers[sourceIndex]
            check(edit.width == mapWidth && edit.height == mapHeight) {
                "TerrainTiler edited a source layer with unexpected dimensions"
            }
            val original = decodeLayerGids(sourceLayer, edit.width, edit.height)
            val updated = original.copyOf()
            edit.cellGids.forEach { (cell, gid) -> updated[cell] = gid }
            existingLayerUpdates += sourceLayer to updated
            expectedPatchedLayers[edit.runtimeLayerIndex] = updated
        } else {
            check(edit.name.lowercase() in acceptedNewNames) {
                "TerrainTiler edited an unsupported new layer '${edit.name}'"
            }
            check(edit.width == mapWidth && edit.height == mapHeight) {
                "A new terrain layer does not match map dimensions"
            }
            val updated = IntArray(cellCount)
            edit.cellGids.forEach { (cell, gid) -> updated[cell] = gid }
            newLayerUpdates[edit.runtimeLayerIndex] = updated
            expectedPatchedLayers[edit.runtimeLayerIndex] = updated
        }
    }

    for (index in sourceLoadedLayers.size until runtimeLayers.size) {
        val layer = runtimeLayers[index]
        val emptyLayer = IntArray(layer.width * layer.height)
        newLayerUpdates.putIfAbsent(index, emptyLayer)
        expectedPatchedLayers.putIfAbsent(index, emptyLayer)
    }

    val newTileSetElements = ArrayList<Element>()
    runtimeNewTilesetIndices.forEach { index ->
        val tileset = runtimeTilesets.getOrNull(index)
            ?: error("A terrain edit references a missing runtime tileset")
        val source = checkNotNull(tileset.source) { "A new terrain tileset has no external source" }
        check(
            source in nativeTerrainTilesetSources &&
                    !source.startsWith('/') && !source.contains('\\') &&
                    source.split('/').none { it == ".." || it.isEmpty() }
        ) { "Unsupported terrain tileset resource path: $source" }
        check(tileset.firstGid > allTilesetElements.maxOfOrNull { it.requiredInt("firstgid") }.orZero()) {
            "A new terrain tileset overlaps the source tileset GID range"
        }
        check(newTileSetElements.none { it.getAttribute("source") == source }) {
            "Duplicate new terrain tileset source"
        }
        val element = document.createElement("tileset")
        element.setAttribute("firstgid", tileset.firstGid.toString())
        element.setAttribute("source", source)
        newTileSetElements += element
    }

    val firstLayer = allLayerElements.firstOrNull()
    newTileSetElements.forEach { element ->
        if (firstLayer == null) root.appendChild(element) else root.insertBefore(element, firstLayer)
    }
    existingLayerUpdates.forEach { (layer, gids) -> writeLayerGids(document, layer, gids) }

    if (newLayerUpdates.isNotEmpty()) {
        val insertionPoint = allLayerElements.lastOrNull()?.nextSibling
        newLayerUpdates.toSortedMap().forEach { (runtimeIndex, gids) ->
            val layout = runtimeLayers[runtimeIndex]
            val element = document.createElement("layer")
            element.setAttribute("name", layout.name)
            element.setAttribute("width", layout.width.toString())
            element.setAttribute("height", layout.height.toString())
            element.appendChild(document.createElement("data"))
            writeLayerGids(document, element, gids)
            if (insertionPoint == null) root.appendChild(element) else root.insertBefore(element, insertionPoint)
        }
    }

    val result = serializeTmx(document)
    val expectedTilesets = runtimeTilesets.take(allTilesetElements.size) +
            runtimeNewTilesetIndices.map(runtimeTilesets::get)
    validateTmxRoundTrip(result, mapWidth, mapHeight, runtimeLayers, expectedTilesets, expectedPatchedLayers)
    return result
}

internal fun decodeSandboxTmxLayerGids(layer: Element, width: Int, height: Int): IntArray =
    decodeLayerGids(layer, width, height)

private fun decodeLayerGids(layer: Element, width: Int, height: Int): IntArray {
    check(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE / 4) {
        "TMX layer dimensions are invalid"
    }
    val dataElements = layer.directChildren("data")
    check(dataElements.size == 1) { "A TMX layer must contain one data element" }
    val data = dataElements.single()
    check(data.getAttribute("encoding") == "base64") {
        "Only native base64 layer data can be edited safely"
    }
    val expectedBytes = width * height * 4
    check(data.textContent.length.toLong() <= expectedBytes.toLong() * 4L + 4096L) {
        "TMX layer data is unexpectedly large for its dimensions"
    }
    val compressed = decodeNativeBase64(data.textContent)
    val decoded = when (data.getAttribute("compression")) {
        "gzip" -> GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readExactly(expectedBytes) }
        "zlib" -> InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readExactly(expectedBytes) }
        "" -> compressed.also { check(it.size == expectedBytes) { "TMX layer data length does not match its dimensions" } }
        else -> error("Unsupported native TMX compression '${data.getAttribute("compression")}'")
    }
    val tileCount = width * height
    check(decoded.size == tileCount * 4) { "TMX layer data length does not match its dimensions" }
    return IntArray(tileCount) { index ->
        val offset = index * 4
        (decoded[offset].toInt() and 0xff) or
                ((decoded[offset + 1].toInt() and 0xff) shl 8) or
                ((decoded[offset + 2].toInt() and 0xff) shl 16) or
                ((decoded[offset + 3].toInt() and 0xff) shl 24)
    }
}

private fun decodeNativeBase64(text: String): ByteArray {
    fun valueOf(character: Char): Int = character.code.takeIf { it < nativeBase64Values.size }
        ?.let(nativeBase64Values::get) ?: -1

    val validCount = text.count { valueOf(it) >= 0 }
    val size = (validCount / 4) * 3 + when (validCount % 4) {
        2 -> 1
        3 -> 2
        else -> 0
    }
    val output = ByteArray(size)
    var buffer = 0
    var bits = 0
    var write = 0
    text.forEach { character ->
        val value = valueOf(character)
        if (value < 0) return@forEach
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            if (write < output.size) output[write++] = ((buffer ushr bits) and 0xff).toByte()
        }
    }
    check(write == output.size) { "Invalid base64 layer data" }
    return output
}

private fun InputStream.readExactly(expectedSize: Int): ByteArray {
    val output = ByteArray(expectedSize)
    var offset = 0
    while (offset < expectedSize) {
        val count = read(output, offset, expectedSize - offset)
        if (count < 0) error("TMX layer data ended before its dimensions were filled")
        if (count == 0) {
            val value = read()
            if (value < 0) error("TMX layer data ended before its dimensions were filled")
            output[offset++] = value.toByte()
        } else {
            offset += count
        }
    }
    check(read() == -1) { "TMX layer data contains bytes beyond its dimensions" }
    return output
}

private fun writeLayerGids(document: Document, layer: Element, gids: IntArray) {
    val data = layer.directChildren("data").singleOrNull()
        ?: document.createElement("data").also(layer::appendChild)
    val raw = ByteArrayOutputStream(gids.size * 4)
    gids.forEach { gid ->
        raw.write(gid and 0xff)
        raw.write((gid ushr 8) and 0xff)
        raw.write((gid ushr 16) and 0xff)
        raw.write((gid ushr 24) and 0xff)
    }
    val zipped = ByteArrayOutputStream()
    GZIPOutputStream(zipped).use { it.write(raw.toByteArray()) }
    data.setAttribute("encoding", "base64")
    data.setAttribute("compression", "gzip")
    while (data.firstChild != null) data.removeChild(data.firstChild)
    data.appendChild(document.createTextNode(encodeBase64(zipped.toByteArray())))
}

private fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xff
        val hasSecond = index + 1 < bytes.size
        val hasThird = index + 2 < bytes.size
        val second = if (hasSecond) bytes[index + 1].toInt() and 0xff else 0
        val third = if (hasThird) bytes[index + 2].toInt() and 0xff else 0
        output.append(alphabet[first ushr 2])
        output.append(alphabet[((first and 3) shl 4) or (second ushr 4)])
        output.append(if (hasSecond) alphabet[((second and 15) shl 2) or (third ushr 6)] else '=')
        output.append(if (hasThird) alphabet[third and 63] else '=')
        index += 3
    }
    return output.toString()
}

private fun validateTmxRoundTrip(
    xml: ByteArray,
    mapWidth: Int,
    mapHeight: Int,
    runtimeLayers: List<SandboxTmxLayerLayout>,
    runtimeTilesets: List<SandboxTmxTilesetLayout>,
    expectedPatchedLayers: Map<Int, IntArray>,
) {
    val root = parseTmx(xml).documentElement
    check(root.requiredInt("width") == mapWidth && root.requiredInt("height") == mapHeight) {
        "TMX dimensions changed during terrain patch serialization"
    }
    val exportedLayers = root.directChildren("layer").filterNot { element ->
        val name = element.getAttribute("name")
        name.equals("set", true) || name.equals("set-disabled", true)
    }
    check(exportedLayers.size == runtimeLayers.size) { "Exported layer count differs from live map" }
    runtimeLayers.forEachIndexed { index, live ->
        val exported = exportedLayers[index]
        check(live.name.equals(exported.getAttribute("name"), true)) {
            "Exported layer order differs from live map"
        }
        check(live.width == exported.requiredInt("width") && live.height == exported.requiredInt("height")) {
            "Exported layer dimensions differ from live map"
        }
    }

    val exportedTilesets = root.directChildren("tileset")
    check(exportedTilesets.size == runtimeTilesets.size) { "Exported tileset count differs from live map" }
    runtimeTilesets.forEachIndexed { index, live ->
        val exported = exportedTilesets[index]
        check(live.firstGid == exported.requiredInt("firstgid")) {
            "Exported tileset GID layout differs from live map"
        }
        check(live.source == exported.getAttribute("source").takeIf(String::isNotEmpty)) {
            "Exported tileset source differs from live map"
        }
    }

    expectedPatchedLayers.forEach { (index, expected) ->
        val layout = runtimeLayers[index]
        val actual = decodeLayerGids(exportedLayers[index], layout.width, layout.height)
        check(actual.contentEquals(expected)) {
            "Terrain layer GIDs, including untouched source values, failed TMX round-trip validation"
        }
    }
}

private fun parseTmx(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(ByteArrayInputStream(bytes))
}

private fun serializeTmx(document: Document): ByteArray {
    val output = ByteArrayOutputStream()
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
    }
    transformer.transform(DOMSource(document), StreamResult(output))
    return output.toByteArray()
}

private fun Element.directChildren(name: String): List<Element> {
    val result = ArrayList<Element>()
    var child: Node? = firstChild
    while (child != null) {
        if (child is Element && child.tagName == name) result += child
        child = child.nextSibling
    }
    return result
}

private fun Element.requiredInt(name: String): Int {
    val value = getAttribute(name)
    check(value.isNotEmpty()) { "TMX element <$tagName> is missing '$name'" }
    return value.toIntOrNull() ?: error("TMX element <$tagName> has invalid '$name' value '$value'")
}

private fun Int?.orZero() = this ?: 0
