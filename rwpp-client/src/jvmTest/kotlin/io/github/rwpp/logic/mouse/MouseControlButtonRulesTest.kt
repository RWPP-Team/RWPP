package io.github.rwpp.logic.mouse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MouseControlButtonRulesTest {
    @Test
    fun orderButtonUsesNativeOppositeSelectionConventionAndKeepsNativeWhenUnset() {
        assertEquals(2, MouseControlButtonRules.nativeMouseOrders(1, 1))
        assertEquals(2, MouseControlButtonRules.nativeMouseOrders(1, 0))
        assertEquals(1, MouseControlButtonRules.nativeMouseOrders(2, 2))
        assertEquals(0, MouseControlButtonRules.nativeMouseOrders(0, 0))
        assertEquals(0, MouseControlButtonRules.selectionSlickButton(0))
        assertEquals(0, MouseControlButtonRules.selectionSlickButton(1))
        assertEquals(1, MouseControlButtonRules.selectionSlickButton(2))
    }

    @Test
    fun confirmationSettingsAreScopedAndCancelUsesOppositeMouseButton() {
        assertEquals(2, MouseControlButtonRules.confirmButton("placeBuilding", 2, emptyMap()))
        assertEquals(1, MouseControlButtonRules.confirmButton("attackMove", 2, mapOf("attackMove" to 1)))
        assertNull(MouseControlButtonRules.confirmButton("guardUnit", 0, emptyMap()))
        assertEquals(2, MouseControlButtonRules.oppositeNativeButton(1))
        assertEquals(1, MouseControlButtonRules.oppositeNativeButton(2))
    }
}
