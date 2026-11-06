/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.coil

import coil3.fetch.SourceFetchResult
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapPreviewFetcherTest {
    private fun map() = GameMap(
        0, "custom", GameSetupMapType.customMap,
        { ByteArrayInputStream(ByteArray(0)) },
        enginePath = "/SD/rusted_warfare_maps/custom.tmx",
    )

    private fun fetchBytes(
        native: ByteArray?,
        rendered: ByteArray?,
        renderedCalls: MutableList<GameMap>,
    ): ByteArray {
        val fetcher = MapPreviewFetcher(
            map = map(),
            nativePreview = { native?.let(::ByteArrayInputStream) },
            renderedPreview = { renderedCalls += it; rendered?.let(::ByteArrayInputStream) },
        )
        val result = runBlocking { fetcher.fetch() } as SourceFetchResult
        return result.source.file().let { path ->
            result.source.fileSystem.read(path) { readByteArray() }
        }
    }

    @Test
    fun rendersTheTmxWhenTheMapShipsNoNativePng() {
        val generated = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
        val calls = mutableListOf<GameMap>()
        assertContentEquals(generated, fetchBytes(native = null, rendered = generated, renderedCalls = calls))
        assertEquals(1, calls.size, "A map without a PNG must be rendered on demand")
    }

    @Test
    fun prefersThePngBesideTheTmxAndSkipsRendering() {
        val nativePng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 9, 8, 7)
        val calls = mutableListOf<GameMap>()
        assertContentEquals(
            nativePng,
            fetchBytes(native = nativePng, rendered = byteArrayOf(42), renderedCalls = calls),
        )
        assertTrue(calls.isEmpty(), "An existing _map.png must not trigger TMX rendering")
    }

    @Test
    fun previewKeysIncludeTheSchemaVersionAndTheFullMapPath() {
        val key = mapPreviewKey(map())
        assertTrue(key.contains("map-preview-v"), "Cached placeholders from older versions must not be reused")
        assertTrue(key.contains("/SD/rusted_warfare_maps/custom.tmx"))
    }
}
