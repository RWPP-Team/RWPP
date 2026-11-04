package io.github.rwpp.api.game.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizedMapNamesTest {
    @Test
    fun repeatedLookupsTranslateOnceAndLocaleReloadRefreshesTheName() {
        val names = LocalizedMapNames()
        var calls = 0
        repeat(100) { assertEquals("Coast", names.get(1, "maps/coast.tmx") { calls++; "Coast" }) }
        assertEquals(1, calls)
        assertEquals("海岸", names.get(2, "maps/coast.tmx") { calls++; "海岸" })
        assertEquals(2, calls)
    }

    @Test
    fun theCacheIsBoundedAndDifferentPathsKeepSeparateEntries() {
        val names = LocalizedMapNames(limit = 2)
        assertEquals("A", names.get(1, "mod-a/map.tmx") { "A" })
        assertEquals("B", names.get(1, "mod-b/map.tmx") { "B" })
        names.get(1, "third.tmx") { "Third" }
        assertEquals("Reloaded A", names.get(1, "mod-a/map.tmx") { "Reloaded A" })
    }
}
