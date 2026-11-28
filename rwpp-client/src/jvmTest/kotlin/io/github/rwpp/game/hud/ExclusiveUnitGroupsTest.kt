package io.github.rwpp.game.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class ExclusiveUnitGroupsTest {
    @Test
    fun legacyDuplicatesKeepTheLowestSlotAndRemoveRepeatEntries() {
        val slots = listOf(mutableListOf(1L, 2L, 2L), mutableListOf(2L, 3L), mutableListOf(1L, 4L))

        retainExclusiveGroupMembers(slots) { it }

        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L)), slots)
    }

    @Test
    fun assignmentMovesUnitsToTheDestinationAndKeepsOtherMembers() {
        val slots = listOf(mutableListOf(1L, 2L), mutableListOf(3L, 4L), mutableListOf(5L, 1L, 3L))

        retainExclusiveGroupMembers(slots, preferredIndex = 2) { it }

        assertEquals(listOf(listOf(2L), listOf(4L), listOf(5L, 1L, 3L)), slots)
    }
}
