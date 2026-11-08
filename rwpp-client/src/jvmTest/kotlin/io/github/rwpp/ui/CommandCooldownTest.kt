package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandCooldownTest {
    @Test
    fun remainingCooldownChangesInvalidateOtherwiseIdenticalCommandFrames() {
        val button = CommandButton("Blink", cooldown = 1f, clickSound = null)
        val start = CommandFrame(buttons = listOf(button))
        val halfway = start.copy(buttons = listOf(button.copy(cooldown = 0.5f)))
        val ready = start.copy(buttons = listOf(button.copy(cooldown = 0f)))
        assertFalse(sameCommandVisuals(start, halfway))
        assertFalse(sameCommandVisuals(halfway, ready))
        assertTrue(sameCommandVisuals(ready, ready.copy()))
    }
}
