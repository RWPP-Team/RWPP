package io.github.rwpp.ui

import io.github.rwpp.game.map.*

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxTmxPatcherTest {
    @Test
    fun editsOnlyRecordedCellsAndPreservesUntouchedFlipFlags() {
        val original = intArrayOf(
            0x80000001.toInt(),
            0xA0000002.toInt(),
            0x60000003,
            0xE0000004.toInt(),
        )
        val patched = patchSandboxTmx(
            sourceMap(width = 4, height = 1, gids = original),
            mapWidth = 4,
            mapHeight = 1,
            runtimeLayers = listOf(SandboxTmxLayerLayout("ground", 4, 1)),
            runtimeTilesets = listOf(SandboxTmxTilesetLayout(1, "terrain/Base.tsx")),
            edits = listOf(
                SandboxTmxLayerEdit(
                    runtimeLayerIndex = 0,
                    name = "ground",
                    width = 4,
                    height = 1,
                    cellGids = mapOf(1 to 6),
                ),
            ),
        )

        assertContentEquals(
            intArrayOf(original[0], 6, original[2], original[3]),
            readLayerGids(patched, 0, 4, 1),
        )
    }

    @Test
    fun addsTerrainTilesetAndNewDetailLayerWithRecordedGids() {
        val patched = patchSandboxTmx(
            sourceMap(width = 2, height = 1, gids = intArrayOf(1, 1)),
            mapWidth = 2,
            mapHeight = 1,
            runtimeLayers = listOf(
                SandboxTmxLayerLayout("ground", 2, 1),
                SandboxTmxLayerLayout("grounddetails", 2, 1),
            ),
            runtimeTilesets = listOf(
                SandboxTmxTilesetLayout(1, "terrain/Base.tsx"),
                SandboxTmxTilesetLayout(100, "terrain/Sand.tsx"),
            ),
            edits = listOf(
                SandboxTmxLayerEdit(
                    runtimeLayerIndex = 1,
                    name = "grounddetails",
                    width = 2,
                    height = 1,
                    cellGids = mapOf(1 to 100),
                ),
            ),
        )

        val document = parse(patched)
        val root = document.documentElement
        val tilesets = root.getElementsByTagName("tileset")
        assertEquals(2, tilesets.length)
        assertEquals("1", (tilesets.item(0) as Element).getAttribute("firstgid"))
        assertEquals("terrain/Sand.tsx", (tilesets.item(1) as Element).getAttribute("source"))
        assertEquals(2, root.getElementsByTagName("layer").length)
        assertContentEquals(intArrayOf(0, 100), readLayerGids(patched, 1, 2, 1))
    }

    @Test
    fun rejectsUnknownExternalTilesetBeforeProducingPatchedBytes() {
        assertFailsWith<IllegalStateException> {
            patchSandboxTmx(
                sourceMap(width = 1, height = 1, gids = intArrayOf(1)),
                mapWidth = 1,
                mapHeight = 1,
                runtimeLayers = listOf(SandboxTmxLayerLayout("ground", 1, 1)),
                runtimeTilesets = listOf(
                    SandboxTmxTilesetLayout(1, "terrain/Base.tsx"),
                    SandboxTmxTilesetLayout(100, "../external/Custom.tsx"),
                ),
                edits = emptyList(),
            )
        }
    }

    @Test
    fun ordinaryNetworkExportCopiesOriginalBytesExactly() {
        val original = byteArrayOf(0, 13, 10, 0xff.toByte(), 0x1f, 0x8b.toByte())
        val output = ByteArrayOutputStream()

        writeNetworkMapExportContent(
            sandbox = false,
            map = null,
            source = ByteArrayInputStream(original),
            output = output,
        )

        assertContentEquals(original, output.toByteArray())
    }

    private fun sourceMap(width: Int, height: Int, gids: IntArray): ByteArray {
        require(gids.size == width * height)
        val raw = ByteArrayOutputStream(gids.size * 4)
        gids.forEach { gid ->
            repeat(4) { byteIndex -> raw.write(gid ushr (byteIndex * 8)) }
        }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw.toByteArray()) }
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(compressed.toByteArray())
        return """
            <map orientation="orthogonal" width="$width" height="$height" tilewidth="20" tileheight="20">
              <tileset firstgid="1" source="terrain/Base.tsx"/>
              <layer name="ground" width="$width" height="$height">
                <data encoding="base64" compression="gzip">$encoded</data>
              </layer>
            </map>
        """.trimIndent().toByteArray()
    }

    private fun readLayerGids(xml: ByteArray, index: Int, width: Int, height: Int): IntArray {
        val layer = parse(xml).getElementsByTagName("layer").item(index) as Element
        return decodeSandboxTmxLayerGids(layer, width, height)
    }

    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(xml))
}
