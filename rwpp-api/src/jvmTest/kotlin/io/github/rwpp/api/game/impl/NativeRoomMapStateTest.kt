package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import kotlin.test.*

class NativeRoomMapStateTest {
    private val type = GameSetupMapType.skirmishMap
    private fun map(name: String) = GameMap(0, name, type, { byteArrayOf().inputStream() })

    @Test
    fun receivedMapWinsOverPreviousRoomPathAndMissingMapsNeverUseAnotherPreview() {
        val old = map("old")
        val current = map("current")
        val maps = listOf(old, current)
        assertSame(current, resolveRoomMap(maps, type, "current.tmx", old.enginePath))
        assertSame(current, resolveRoomMap(maps, type, "maps\\skirmish\\current.tmx", old.enginePath))
        val missing = resolveRoomMap(maps, type, "remote.tmx", old.enginePath)
        assertTrue(missing is NetworkMap)
        assertEquals("remote", missing.mapName)
        assertSame(old, resolveRoomMap(maps, type, null, old.enginePath))
    }

    @Test
    fun cachedRoomMapIndexKeepsSetupAuthorityWithoutScanningTheCatalog() {
        val old = map("old")
        val current = map("current")
        val index = RoomMapCatalogIndex(listOf(old, current))

        assertSame(current, index.find(type, "current.tmx", old.enginePath))
        assertNull(index.find(type, "remote.tmx", old.enginePath))
        assertSame(old, index.find(type, null, old.enginePath))
    }

    @Test
    fun cachedRoomMapIndexKeepsTheFirstCatalogEntryForDuplicateSetupNames() {
        val first = map("duplicate")
        val later = map("duplicate")
        val index = RoomMapCatalogIndex(listOf(first, later))

        assertSame(first, index.find(type, "duplicate.tmx", later.enginePath))
    }

    @Test
    fun freshRoomDiscardsAllDepartedSetupAndMapPayload() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val network = unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine
        network.gameSetup = GameSetup().apply {
            mapType = GameSetupMapType.customMap; mapName = "third-party.tmx"; fogMode = 0; incomeMultiplier = 8f
        }
        network.currentGameMapPath = "mods/maps/third-party.tmx"
        network.mapName = "verification.tmx"
        network.customMapStream = com.corrodinggames.rts.gameFramework.network.InputNetStream(byteArrayOf(1, 2))
        network.savedGameStream = com.corrodinggames.rts.gameFramework.network.InputNetStream(byteArrayOf(3))
        resetNativeRoomMap(network)
        assertEquals(type, network.gameSetup.mapType)
        assertEquals(GameSetup().mapName, network.gameSetup.mapName)
        assertEquals(2, network.gameSetup.fogMode)
        assertEquals(1f, network.gameSetup.incomeMultiplier)
        assertNull(network.currentGameMapPath)
        assertNull(network.mapName)
        assertNull(network.customMapStream)
        assertNull(network.savedGameStream)
    }
}
