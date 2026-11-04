package io.github.rwpp.graphics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollisionBoundsPolicyTest {
    @Test
    fun collisionDrawingKeepsNativeFogAndUnitVisibilityGuards() {
        assertTrue(collisionBoundsShouldDraw(true, false, false, true))
        assertFalse(collisionBoundsShouldDraw(false, false, false, true))
        assertFalse(collisionBoundsShouldDraw(true, true, false, true))
        assertFalse(collisionBoundsShouldDraw(true, false, true, true))
        assertFalse(collisionBoundsShouldDraw(true, false, false, false))
    }
}
