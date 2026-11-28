package io.github.rwpp.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroUnitGroupsTest {
    @Test
    fun splitsSeparatedUnitsFromTheSameNativeGroup() {
        val groups = AeroUnitGroupClusters()
        for (x in listOf(-20f, 20f, 180f)) groups.add(1, x, 0f, 8f)
        val frames = groups.build()
        assertEquals(listOf(2, 1), frames.map { it.unitCount })
        assertEquals(listOf(1, 1), frames.map { it.number })
        assertTrue(frames[0].bounds.right < frames[1].bounds.left)
    }

    @Test
    fun denseInterleavedGroupsKeepTheirNumbers() {
        val groups = AeroUnitGroupClusters()
        repeat(1600) { index -> groups.add(index % 10 + 1, index % 16 * 2f, index % 20 * 2f, 8f) }
        val frames = groups.build()
        assertEquals((1..10).toList(), frames.map { it.number })
        assertTrue(frames.all { it.unitCount == 160 })
        assertEquals(10, aeroUnitGroupColors.toSet().size)
    }
}
