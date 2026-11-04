package io.github.rwpp.ui

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.game.map.validExportMapName
import io.github.rwpp.game.map.withCurrentMapSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkMapExportTest {
    @Test
    fun rejectsInvalidFileNamesWithoutDisallowingOrdinaryMapNames() {
        assertTrue(validExportMapName("Arena 3"))
        assertTrue(validExportMapName("[p8]test.tmx"))
        for (name in listOf("", ".", "..", "../other", "C:\\map", "bad:name", "map|name", "CON.tmx", "map.")) {
            assertFalse(validExportMapName(name), name)
        }
    }

    @Test
    fun receivedMapRemainsReadableAfterNativeLoadingConsumedItsStream() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
        engine.networkEngine = unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine
        engine.networkEngine.isServer = false
        val bytes = "<map orientation=\"orthogonal\"/>".toByteArray()
        engine.inputNetStream = InputNetStream(bytes)
        val networkStream = engine.inputNetStream.inputStream
        assertContentEquals(bytes, networkStream.readBytes())
        val exported = withCurrentMapSource(engine) { input -> input.use { it.readBytes() } }
        assertContentEquals(bytes, exported)
        assertContentEquals(bytes, networkStream.readBytes(), "Export must leave the engine-owned stream reusable")
    }
}
