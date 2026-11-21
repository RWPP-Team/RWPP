package io.github.rwpp.game.movement

import kotlin.test.*

class MoveNavigationGridTest {
    @Test
    fun anExtraObstacleDoesNotEraseTheEntireCornerPool() {
        fun candidates(obstacles: Int): List<MovePoint> {
            val blocked = BooleanArray(60 * 60)
            repeat(obstacles) { index -> blocked[(8 + index % 7 * 6) * 60 + 8 + index / 7 * 6] = true }
            val grid = MoveNavigationGrid(0f, 0f, 60, 60, 20f, 20f, blocked)
            return grid.corners(MovePoint(40f, 40f), MovePoint(1050f, 1050f), 2f)
        }
        assertEquals(96, candidates(32).size)
        assertEquals(96, candidates(33).size, "More than 256 raw offsets used to return an empty list")
    }

    @Test
    fun sweptBodyRejectsAnEdgeThatItsCenterLineClears() {
        val blocked = BooleanArray(20 * 20)
        blocked[5 * 20 + 5] = true
        val map = MoveNavigationGrid(0f, 0f, 20, 20, 20f, 20f, blocked)
        assertTrue(map.isClear(MovePoint(130f, 80f), MovePoint(130f, 150f), 8f))
        assertFalse(map.isClear(MovePoint(125f, 80f), MovePoint(125f, 150f), 8f))
        assertFalse(map.isClear(MovePoint(105f, 80f), MovePoint(105f, 150f), 1f))
    }

    @Test
    fun discCornerDistanceUsesTheSegmentRatherThanOnlySamplePoints() {
        val blocked = BooleanArray(20 * 20)
        blocked[5 * 20 + 5] = true
        val map = MoveNavigationGrid(0f, 0f, 20, 20, 20f, 20f, blocked)
        assertFalse(map.isClear(MovePoint(140f, 50f), MovePoint(50f, 140f), 8f))
        assertTrue(map.isClear(MovePoint(140f, 50f), MovePoint(50f, 140f), 5f))
    }

    @Test
    fun aSnapshotDoesNotChangeWhenTheNativeSourceArrayChanges() {
        val blocked = BooleanArray(20 * 20)
        blocked[5 * 20 + 5] = true
        val map = MoveNavigationGrid(0f, 0f, 20, 20, 20f, 20f, blocked)
        blocked.fill(false)
        assertFalse(map.isClear(MovePoint(110f, 80f), MovePoint(110f, 150f), 1f))
        assertFalse(map.contains(MovePoint(Float.NaN, 100f)))
        assertFalse(map.contains(MovePoint(400f, 100f)))
        assertFalse(map.contains(MovePoint(2f, 100f), 8f))
    }
}
