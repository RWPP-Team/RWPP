package io.github.rwpp.ui

import io.github.rwpp.game.playerColorOverrideSelection
import io.github.rwpp.game.room.formatRoomMaxPlayerCount
import io.github.rwpp.game.room.normalizeRoomMaxPlayerCount
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomJoinConfigurationRegressionTest {
    @Test
    fun playerColorDropdownUsesTheNativeOverrideIndex() {
        assertEquals(-1, playerColorOverrideSelection(null))
        assertEquals(4, playerColorOverrideSelection(4))
    }

    @Test
    fun maxPlayerCountAlwaysSnapsAndDisplaysAsAnInteger() {
        assertEquals(12, normalizeRoomMaxPlayerCount(11.7f, currentPlayerCount = 2))
        assertEquals(7, normalizeRoomMaxPlayerCount(2.4f, currentPlayerCount = 7))
        assertEquals(100, normalizeRoomMaxPlayerCount(120f, currentPlayerCount = 2))
        assertEquals("12", formatRoomMaxPlayerCount(12f))
    }
}
