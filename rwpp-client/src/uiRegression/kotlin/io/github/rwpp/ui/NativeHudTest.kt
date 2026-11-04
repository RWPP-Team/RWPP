package io.github.rwpp.ui

import io.github.rwpp.game.hud.HudActionRect
import io.github.rwpp.game.hud.HudActionSize
import io.github.rwpp.game.hud.hudQueueBatch
import io.github.rwpp.game.hud.nativeHudActionLayout
import io.github.rwpp.game.minimap.MinimapRefreshSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHudTest {
    @Test
    fun nativeInfoRowsAndDifferentButtonHeightsKeepTheirOrder() {
        val rects = nativeHudActionLayout(
            240, 72, 2, listOf(
                HudActionSize(0.5f, 1), // Native full-width information row.
                HudActionSize(1f, -1), HudActionSize(1f, -1),
                HudActionSize(0.5f, 1), HudActionSize(1.5f, 3)
            )
        )
        assertEquals(HudActionRect(0, 0, 236, 36), rects[0])
        assertEquals(HudActionRect(0, 40, 116, 72), rects[1])
        assertEquals(HudActionRect(120, 40, 116, 72), rects[2])
        assertEquals(HudActionRect(0, 116, 236, 36), rects[3])
        assertEquals(HudActionRect(0, 156, 76, 108), rects[4])
    }

    @Test
    fun shorterButtonsStartANewRowAndModDimensionsStayFinite() {
        val rects = nativeHudActionLayout(
            300, 60, 3,
            listOf(
                HudActionSize(2f, -1), HudActionSize(1f, -1),
                HudActionSize(Float.NaN, -1), HudActionSize(0f, -1)
            )
        )
        assertEquals(124, rects[1].y)
        assertEquals(124, rects[2].y)
        assertEquals(124, rects[3].y)
        assertTrue(rects.all { it.width > 0 && it.height > 0 && it.x + it.width <= 300 })
    }

    @Test
    fun largeMixedActionListsNeverOverlapOrDropEntries() {
        for (width in listOf(120, 256, 640, 1024)) {
            val actions = List(150) { i ->
                HudActionSize(
                    if (i % 7 == 0) 0.5f else 1f,
                    if (i % 11 == 0) 1 else -1
                )
            }
            val rects = nativeHudActionLayout(width, 72, 2, actions)
            assertEquals(actions.size, rects.size)
            rects.forEachIndexed { i, a ->
                assertTrue(a.x >= 0 && a.x + a.width <= width)
                rects.take(i).forEach { b ->
                    assertTrue(
                        a.x >= b.x + b.width || b.x >= a.x + a.width ||
                                a.y >= b.y + b.height || b.y >= a.y + a.height
                    )
                }
            }
        }
    }

    @Test
    fun queueBatchUsesNativeModifierPrecedence() {
        assertEquals(1, hudQueueBatch(false, true, true))
        assertEquals(1, hudQueueBatch(true, false, false))
        assertEquals(5, hudQueueBatch(true, true, false))
        assertEquals(10, hudQueueBatch(true, false, true))
        assertEquals(10, hudQueueBatch(true, true, true))
    }

    @Test
    fun minimapSamplingDoesNotRequireTerrainWorkForEachCameraFrame() {
        val schedule = MinimapRefreshSchedule()
        var units = 0
        var fog = 0
        repeat(60) { frame ->
            val now = frame * 1_000L / 60
            if (schedule.unitsDue(now)) {
                units++; if (schedule.fogDue(now)) fog++
            }
        }
        assertEquals(10, units)
        assertEquals(5, fog)
        assertFalse(schedule.unitsDue(990))
        assertTrue(schedule.unitsDue(1000))
        assertTrue(schedule.fogDue(1000))
    }
}
