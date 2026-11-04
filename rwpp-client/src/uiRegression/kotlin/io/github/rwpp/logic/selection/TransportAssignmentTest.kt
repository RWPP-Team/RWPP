package io.github.rwpp.logic.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportAssignmentTest {
    @Test
    fun reservesWeightedSeatsAndPrioritizesCompatibilityConstrainedUnits() {
        val spaces = listOf(TransportSpace(10, 4, 0f, 0f), TransportSpace(20, 2, 100f, 0f))
        val plan = assignTransportSeats(
            spaces, listOf(
                TransportCandidate(1, 0f, 0f, mapOf(10L to 1, 20L to 1)),
                TransportCandidate(2, 0f, 0f, mapOf(10L to 3)),
                TransportCandidate(3, 0f, 0f, mapOf(10L to 1, 20L to 1)),
                TransportCandidate(4, 0f, 0f, mapOf(10L to 1, 20L to 1)),
                TransportCandidate(5, 0f, 0f, mapOf(10L to 1, 20L to 1)),
            )
        )
        assertEquals(4, plan.size)
        assertEquals(10L, plan.single { it.passengerId == 2L }.carrierId)
        plan.groupBy { it.carrierId }.forEach { (id, assignments) ->
            assertTrue(assignments.sumOf { it.slots } <= spaces.single { it.id == id }.freeSlots)
        }
    }

    @Test
    fun neverAssignsDuplicatesUnknownCarriersOrInvalidSeatCosts() {
        val passenger = TransportCandidate(1, 0f, 0f, mapOf(5L to 1))
        val plan = assignTransportSeats(
            listOf(TransportSpace(5, 2, 0f, 0f)), listOf(
                passenger, passenger,
                TransportCandidate(2, 0f, 0f, mapOf(6L to 1)),
                TransportCandidate(3, 0f, 0f, mapOf(5L to 0)),
                TransportCandidate(4, 0f, 0f, mapOf(5L to 3)),
            )
        )
        assertEquals(listOf(TransportAssignment(1, 5, 1)), plan)
    }

    @Test
    fun usesDistanceWhenCapacitiesAreEqualAndHonorsRemainingSeats() {
        val plan = assignTransportSeats(
            listOf(TransportSpace(10, 1, 0f, 0f), TransportSpace(20, 1, 100f, 0f)), listOf(
                TransportCandidate(1, 90f, 0f, mapOf(10L to 1, 20L to 1)),
                TransportCandidate(2, 90f, 0f, mapOf(10L to 1, 20L to 1)),
            )
        )
        assertEquals(20L, plan.first().carrierId)
        assertEquals(10L, plan.last().carrierId)
    }
}
