package io.github.rwpp.logic.selection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TacticalSelectionPolicyTest {
    @Test
    fun lowHealthUsesInclusiveBoundaryAndRejectsInvalidVitals() {
        assertTrue(isLowHealth(30f, 100f))
        assertTrue(isLowHealth(1f, 100f))
        assertFalse(isLowHealth(30.01f, 100f))
        assertFalse(isLowHealth(0f, 100f))
        assertFalse(isLowHealth(1f, 0f))
        assertFalse(isLowHealth(Float.NaN, 100f))
        assertFalse(isLowHealth(1f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun unselectedScopeIncludesVisibleEdgesButExcludesOffscreenUnits() {
        assertTrue(intersectsSelectionViewport(8f, 30f, 2f, 10f, 20f, 100f, 50f))
        assertFalse(intersectsSelectionViewport(7f, 30f, 2f, 10f, 20f, 100f, 50f))
        assertFalse(intersectsSelectionViewport(25f, 75f, 2f, 10f, 20f, 100f, 50f))
    }
}
