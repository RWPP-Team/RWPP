package io.github.rwpp.api.game.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapNamesTest {
    @Test
    fun modTaggedPathsBecomeTheNameInsideTheModArchive() {
        assertEquals("Foo", MapNames.getPrettyMapName("MOD|a1b2c3/maps/foo.tmx"))
        assertEquals("Foo bar", MapNames.getPrettyMapName("MOD|a1b2c3/maps/foo_bar.tmx"))
        assertEquals(
            "Big map",
            MapNames.getPrettyMapName("/SD/rusted_warfare_maps/MOD|a1b2c3/maps/Big_map.tmx"),
        )
    }

    @Test
    fun virtualTagWithoutAPathKeepsTheOriginalFileName() {
        assertEquals("MOD|a1b2c3", MapNames.getPrettyMapName("MOD|a1b2c3"))
    }

    @Test
    fun newPathPrefixAndBracketedSuffixAreStripped() {
        assertEquals("Foo", MapNames.getPrettyMapName("NEW_PATH|maps2/foo.tmx"))
        assertEquals("Foo", MapNames.getPrettyMapName("[p2]foo.tmx"))
    }

    @Test
    fun ordinaryPathsKeepTheirExistingPrettyName() {
        assertEquals("10p Dividing River", MapNames.getPrettyMapName("maps/normal/10p_Dividing_River.tmx"))
        assertEquals("Save", MapNames.getPrettyMapName("saves/Save.rwsave"))
    }

    @Test
    fun stripKeepsOrdinaryPathsAndRemovesOnlyTheTag() {
        assertEquals("maps/foo.tmx", MapNames.stripVirtualMapPrefix("maps/foo.tmx"))
        assertEquals("maps/foo.tmx", MapNames.stripVirtualMapPrefix("MOD|a1b2c3/maps/foo.tmx"))
        assertEquals(
            "maps/foo.tmx",
            MapNames.stripVirtualMapPrefix("/SD/rusted_warfare_maps/MOD|a1b2c3/maps/foo.tmx"),
        )
    }
}
