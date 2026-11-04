package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.rwpp.game.graphics.AeroOrderClicks
import io.github.rwpp.graphics.AeroRangeCircle
import io.github.rwpp.graphics.AeroRangeTracker
import kotlin.test.*

class HudSnapshotReuseTest {
    @Test
    fun orderSnapshotsAreReusedWithoutMutatingPublishedFrames() {
        AeroOrderClicks.clear()
        try {
            AeroOrderClicks.add(1f, 2f, false, 100L)
            val first = AeroOrderClicks.frame(110L)
            assertSame(first, AeroOrderClicks.frame(200L))
            AeroOrderClicks.add(3f, 4f, true, 250L)
            val second = AeroOrderClicks.frame(260L)
            assertNotSame(first, second)
            assertEquals(1, first.size)
            assertEquals(2, second.size)
            assertTrue(AeroOrderClicks.frame(1000L).isEmpty())
        } finally {
            AeroOrderClicks.clear()
        }
    }

    @Test
    fun rangeSnapshotsRemainStableAndOnlyChangeWhenSamplesChange() {
        val tracker = AeroRangeTracker<String>()
        tracker.add("one", AeroRangeCircle(Offset.Zero, 10f, 1, Color.White))
        val first = tracker.snapshot()
        assertSame(first, tracker.snapshot())
        tracker.clear()
        val empty = tracker.snapshot()
        assertTrue(empty.isEmpty())
        assertEquals(1, first.size)
        assertSame(empty, tracker.snapshot())
    }
}
