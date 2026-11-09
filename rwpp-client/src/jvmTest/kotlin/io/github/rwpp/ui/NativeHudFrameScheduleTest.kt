package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHudFrameScheduleTest {
    @Test
    fun native60HzDoesNotWaitForALateWindowCallback() {
        val schedule = NativeHudFrameSchedule()
        repeat(60) { frame ->
            val now = frame * 16_666_667L
            schedule.onNativeFrame(now)
            // The UI callback arrives after this native draw. It must not queue another batch
            // for the following native update and introduce a permanent one-frame delay.
            assertFalse(schedule.needsDisplayFallback(now + 12_000_000L))
        }
    }

    @Test
    fun native120HzFramesSuppress60HzFallbackAndResumeAfterSurfaceStops() {
        val schedule = NativeHudFrameSchedule()
        assertTrue(schedule.needsDisplayFallback(0L))
        repeat(120) { frame ->
            val now = frame * 8_333_333L
            schedule.onNativeFrame(now)
            assertFalse(schedule.needsDisplayFallback(now + 8_000_000L))
        }
        val last = 119 * 8_333_333L
        assertFalse(schedule.needsDisplayFallback(last + 99_999_999L))
        assertTrue(schedule.needsDisplayFallback(last + 100_000_000L))
        schedule.onNativeFrame(last + 200_000_000L)
        assertFalse(schedule.needsDisplayFallback(last + 200_000_001L))
    }
}
