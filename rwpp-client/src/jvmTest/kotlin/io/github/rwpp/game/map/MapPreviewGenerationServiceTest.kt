/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受  RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MapPreviewGenerationServiceTest {
    @Test
    fun supportsNativeLegacyTilesetImagePaths() {
        assertTrue(
            "tilesets/bitmaps/mountain.png" in previewResourceCandidates(
                "terrain/bitmaps/mountain.png", "tilesets", "maps/skirmish/test.tmx",
            )
        )
        assertTrue(
            "tilesets/misc.tsx" in previewResourceCandidates(
                "../rustedWarfareMaps-testing/misc.tsx", "maps/skirmish", "maps/skirmish/test.tmx",
            )
        )
    }

    @Test
    fun rendersCompressedGidsAndHorizontalTileFlipsFromLocalAssets() = runBlocking {
        val image = MapPreviewRaster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        val png = encodeMapPreviewPng(image)
        val littleEndianGids = byteArrayOf(
            1, 0, 0, 0,
            1, 0, 0, 0,
        )
        val compressedGids = ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).use { it.write(littleEndianGids) }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        val xml = """
            <map orientation="orthogonal" width="2" height="1" tilewidth="2" tileheight="1">
              <tileset firstgid="1" name="test" tilewidth="2" tileheight="1" tilecount="1" columns="1">
                <image source="test.png" width="2" height="1"/>
              </tileset>
              <layer name="Ground" width="2" height="1" opacity="1">
                <data encoding="base64" compression="gzip">$compressedGids</data>
              </layer>
              <layer name="Flip" width="2" height="1" opacity="1">
                <data encoding="csv">0,2147483649</data>
              </layer>
            </map>
        """.trimIndent().encodeToByteArray()
        val rendered = renderMapPreview(
            tmxBytes = xml,
            mapPath = "maps/skirmish/test.tmx",
            maxDimensionPx = 64,
            readResource = { path -> png.takeIf { path == "tilesets/test.png" } },
            decodeImage = ::decodeMapPreviewImage,
        )
        assertEquals(4, rendered.width)
        assertEquals(1, rendered.height)
        assertEquals(0xFFFF0000.toInt(), rendered.pixels[0])
        assertEquals(0xFF0000FF.toInt(), rendered.pixels[1])
        assertEquals(0xFF0000FF.toInt(), rendered.pixels[2])
        assertEquals(0xFFFF0000.toInt(), rendered.pixels[3])
    }

    @Test
    fun rejectsDoctypeAndResourceTraversal() {
        val externalEntity = """
            <!DOCTYPE map [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <map orientation="orthogonal" width="1" height="1" tilewidth="1" tileheight="1">&xxe;</map>
        """.trimIndent().encodeToByteArray()
        assertFails {
            runBlocking {
                renderMapPreview(
                    externalEntity, "maps/skirmish/test.tmx", 64, { null }, ::decodeMapPreviewImage
                )
            }
        }
        assertTrue(previewResourceCandidates("../../outside.png", "tilesets/sub", "maps/test.tmx").isEmpty())
    }
}
