package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AeroGameStatusTest {
    @Test
    fun creditsTextKeepsTheNativeAmountAndDeltaShape() {
        assertEquals("$1200 (+5)(-2)", aeroCreditsText(1200, 5, -2))
        assertEquals("$0 (+0)", aeroCreditsText(0, 0, 0))
        assertEquals("$75 (-3)", aeroCreditsText(75, -3, 0))
    }
}
