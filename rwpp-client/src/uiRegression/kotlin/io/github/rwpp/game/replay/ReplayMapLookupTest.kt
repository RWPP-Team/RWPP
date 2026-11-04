package io.github.rwpp.game.replay

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertSame

class ReplayMapLookupTest {
    @Test
    fun lookupMatchesPrettyAndEngineNamesWithoutScanningPerReplay() {
        val map = GameMap(
            id = 1,
            mapName = "[z;p10]Coastline (8p)",
            mapType = GameSetupMapType.skirmishMap,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
            enginePath = "maps/skirmish/[z;p10]Coastline (8p).tmx",
        )
        val lookup = ReplayMapLookup(listOf(map))

        assertSame(map, lookup.find("coastline (8P)"))
        assertSame(map, lookup.find("[Z;P10]COASTLINE (8p)"))
    }

    @Test
    fun duplicateAliasesKeepTheFirstMapCatalogMatch() {
        val earlier = fixtureMap(1, "Alias", "Earlier")
        val later = fixtureMap(2, "Other", "Alias")

        assertSame(earlier, ReplayMapLookup(listOf(earlier, later)).find("alias"))
    }

    private fun fixtureMap(id: Int, mapName: String, displayName: String): GameMap =
        object : GameMap(
            id = id,
            mapName = mapName,
            mapType = GameSetupMapType.skirmishMap,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
        ) {
            override fun displayName(): String = displayName
        }
}
