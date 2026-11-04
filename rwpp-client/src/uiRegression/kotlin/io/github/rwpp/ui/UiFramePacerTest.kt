package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiFramePacerTest {
    @Test
    fun nativeFramesContinueWhileUiRefreshesAtItsOwnRate() {
        val pacer = UiFramePacer()
        val rendered = (0 until 300).count { pacer.shouldRender(it * 1_000_000_000L / 300, 60) }
        assertTrue(rendered in 59..61, "UI refreshes: $rendered for 300 native frames")
    }

    @Test
    fun inputAndRateChangesAreImmediateAndStallsDoNotCauseCatchupBursts() {
        val pacer = UiFramePacer()
        assertTrue(pacer.shouldRender(0, 60))
        assertFalse(pacer.shouldRender(1_000_000, 60))
        pacer.request()
        assertTrue(pacer.shouldRender(2_000_000, 60))
        assertTrue(pacer.shouldRender(3_000_000, 120))
        assertTrue(pacer.shouldRender(5_000_000_000L, 120))
        assertFalse(pacer.shouldRender(5_000_000_001L, 120))
    }
}
