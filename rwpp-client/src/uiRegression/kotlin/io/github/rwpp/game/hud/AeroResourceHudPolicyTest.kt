package io.github.rwpp.game.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AeroResourceHudPolicyTest {
    @Test
    fun samplesAtTheConfiguredCadenceAndPublishesOnlyChangedRows() {
        assertTrue(resourceHudSampleDue(Long.MIN_VALUE, 1_000L, 200L))
        assertFalse(resourceHudSampleDue(1_000L, 1_199L, 200L))
        assertTrue(resourceHudSampleDue(1_000L, 1_200L, 200L))

        val rows = listOf(AeroResourceHudLine("\$42", 0xff00ff00.toInt(), false))
        assertFalse(resourceHudRowsChanged(rows, rows.toList()))
        assertTrue(resourceHudRowsChanged(rows, rows + AeroResourceHudLine("+3", null, true)))
    }

    @Test
    fun resourceRowsHonorZeroVisibilityAndSixAppendLevels() {
        assertTrue(resourceHudShouldDisplay(0.0, displayWhenZero = true, displayInHud = true, depth = 0))
        assertFalse(resourceHudShouldDisplay(0.0, displayWhenZero = false, displayInHud = true, depth = 0))
        assertFalse(resourceHudShouldDisplay(2.0, displayWhenZero = false, displayInHud = false, depth = 0))
        assertTrue(resourceHudShouldDisplay(2.0, displayWhenZero = false, displayInHud = false, depth = 1))

        assertTrue(resourceHudShouldRecurse(0, hasAppendedResource = true, amount = 0.0, appendResourceWhenZero = true))
        assertFalse(
            resourceHudShouldRecurse(
                0,
                hasAppendedResource = true,
                amount = 0.0,
                appendResourceWhenZero = false
            )
        )
        assertTrue(
            resourceHudShouldRecurse(
                5,
                hasAppendedResource = true,
                amount = 1.0,
                appendResourceWhenZero = false
            )
        )
        assertFalse(
            resourceHudShouldRecurse(
                6,
                hasAppendedResource = true,
                amount = 1.0,
                appendResourceWhenZero = true
            )
        )
    }

    @Test
    fun formattedResourceRatesPreserveNativeIncomeAndExpenseText() {
        assertEquals("\$42(+5)(-2)", formatResourceHudLine("\$42", income = 5, expense = 2))
        assertEquals("\$42(+5)", formatResourceHudLine("\$42", income = 5, expense = 0))
        assertEquals("\$42(-3)", formatResourceHudLine("\$42", income = -3, expense = 0))
        assertEquals("\$42", formatResourceHudLine("\$42", income = 0, expense = 0))
    }
}
