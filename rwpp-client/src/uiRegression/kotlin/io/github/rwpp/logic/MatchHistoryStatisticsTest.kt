package io.github.rwpp.logic

import kotlin.test.*

class MatchHistoryStatisticsTest {
    @Test
    fun productionCounterExcludesInitialRosterAndDeduplicatesCompletionsByIdentity() {
        val startingUnit = Any()
        val producedUnit = Any()
        val completedBuilding = Any()
        val otherTeamUnit = Any()
        val counter = MatchHistoryProductionCounter()

        counter.begin(listOf(startingUnit))

        assertFalse(counter.recordCompleted(startingUnit, teamId = 0, isBuilding = false))
        assertTrue(counter.recordCompleted(producedUnit, teamId = 0, isBuilding = false))
        assertFalse(counter.recordCompleted(producedUnit, teamId = 0, isBuilding = false))
        assertTrue(counter.recordCompleted(completedBuilding, teamId = 0, isBuilding = true))
        assertTrue(counter.recordCompleted(otherTeamUnit, teamId = 1, isBuilding = false))

        assertEquals(MatchHistoryBuiltCounts(units = 1, buildings = 1), counter.countsFor(0))
        assertEquals(MatchHistoryBuiltCounts(units = 1, buildings = 0), counter.countsFor(1))
        counter.reset()
        assertNull(counter.countsFor(0), "No active session means build totals are unavailable")
        assertFalse(counter.recordCompleted(Any(), teamId = 0, isBuilding = false))
    }

    @Test
    fun productionEventPolicyRejectsReplayObserverAndUnfinishedUnits() {
        fun eligible(
            activeMatch: Boolean = true,
            replay: Boolean = false,
            teamId: Int? = 0,
            observer: Boolean = false,
            alive: Boolean = true,
            complete: Boolean = true,
            placeholder: Boolean = false,
            virtual: Boolean = false,
            attached: Boolean = false,
        ) = shouldCountMatchHistoryProductionEvent(
            activeMatch, replay, teamId, observer, alive, complete, placeholder, virtual, attached,
        )

        assertTrue(eligible())
        assertFalse(eligible(activeMatch = false))
        assertFalse(eligible(replay = true))
        assertFalse(eligible(observer = true))
        assertFalse(eligible(teamId = null))
        assertFalse(eligible(alive = false))
        assertFalse(eligible(complete = false))
        assertFalse(eligible(placeholder = true))
        assertFalse(eligible(virtual = true))
        assertFalse(eligible(attached = true))
    }

    @Test
    fun remainingRosterExcludesDeadIncompletePlaceholderAndVirtualUnits() {
        val totals = countMatchHistoryRemainingUnits(
            listOf(
                MatchHistoryUnitSnapshot(0, true, true, false),
                MatchHistoryUnitSnapshot(0, true, true, true),
                MatchHistoryUnitSnapshot(0, false, true, false),
                MatchHistoryUnitSnapshot(0, true, false, true),
                MatchHistoryUnitSnapshot(0, true, true, false, isPlaceholder = true),
                MatchHistoryUnitSnapshot(0, true, true, false, isVirtual = true),
                MatchHistoryUnitSnapshot(0, true, true, false, isAttached = true),
                // Transported units are still owned and remain in the roster count.
                MatchHistoryUnitSnapshot(1, true, true, false, isTransported = true),
                MatchHistoryUnitSnapshot(null, true, true, false),
            ),
        )

        assertEquals(MatchHistoryRemainingCounts(units = 1, buildings = 1), totals[0])
        assertEquals(MatchHistoryRemainingCounts(units = 1, buildings = 0), totals[1])
        assertEquals(setOf(0, 1), totals.keys)
    }

    @Test
    fun unitKillDeathUsesNativeNonBuildingCategoriesAndHasNoRatioWithoutLosses() {
        // Experimental units are counted separately by native StatsHandler, while buildings have
        // their own category and do not enter unit K/D.
        assertEquals(
            3.0, matchHistoryUnitKillDeathRatio(
                unitsKilled = 8,
                experimentalsKilled = 4,
                unitsLost = 3,
                experimentalsLost = 1,
            )
        )
        assertNull(matchHistoryUnitKillDeathRatio(4, 1, 0, 0))
    }

    @Test
    fun playerPerformanceDetailsStartCollapsedAndToggleIndependently() {
        val initial = MatchHistoryPerformanceDisclosure()
        assertFalse(initial.isExpanded(2))

        val oneExpanded = initial.toggle(2)
        assertTrue(oneExpanded.isExpanded(2))
        assertFalse(oneExpanded.isExpanded(3))

        val twoExpanded = oneExpanded.toggle(3)
        assertTrue(twoExpanded.isExpanded(2))
        assertTrue(twoExpanded.isExpanded(3))
        assertFalse(twoExpanded.toggle(2).isExpanded(2))
    }
}
