/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import java.util.*

/** Completed units counted after the session's initial roster has been excluded. */
internal data class MatchHistoryBuiltCounts(
    val units: Int = 0,
    val buildings: Int = 0,
)

/**
 * Counts completed production/construction events once per native unit identity.
 * Native creation callbacks also occur during map setup, so [begin] snapshots and excludes that
 * initial roster. Identity semantics avoid conflating separate units with equal native IDs/data.
 */
internal class MatchHistoryProductionCounter {
    private val initialRoster = identitySet()
    private val countedUnits = identitySet()
    private val countsByTeam = mutableMapOf<Int, MatchHistoryBuiltCounts>()
    private var active = false

    @Synchronized
    fun begin(initialUnits: Iterable<Any>) {
        reset()
        initialRoster.addAll(initialUnits)
        active = true
    }

    /** Returns true only when a completed post-start unit was newly counted. */
    @Synchronized
    fun recordCompleted(unit: Any, teamId: Int?, isBuilding: Boolean): Boolean {
        if (!active || teamId == null || unit in initialRoster || !countedUnits.add(unit)) return false
        val old = countsByTeam[teamId] ?: MatchHistoryBuiltCounts()
        countsByTeam[teamId] = if (isBuilding) {
            old.copy(buildings = old.buildings + 1)
        } else {
            old.copy(units = old.units + 1)
        }
        return true
    }

    @Synchronized
    fun countsFor(teamId: Int): MatchHistoryBuiltCounts? =
        if (active) countsByTeam[teamId] ?: MatchHistoryBuiltCounts() else null

    @Synchronized
    fun reset() {
        active = false
        initialRoster.clear()
        countedUnits.clear()
        countsByTeam.clear()
    }

}

internal data class MatchHistoryUnitSnapshot(
    val teamId: Int?,
    val isAlive: Boolean,
    val isComplete: Boolean,
    val isBuilding: Boolean,
    val isPlaceholder: Boolean = false,
    val isVirtual: Boolean = false,
    val isAttached: Boolean = false,
    /** Transport does not remove ownership, so transported units remain in remaining totals. */
    val isTransported: Boolean = false,
)

internal data class MatchHistoryRemainingCounts(
    val units: Int = 0,
    val buildings: Int = 0,
)

internal fun shouldCountMatchHistoryProductionEvent(
    activeMatch: Boolean,
    replay: Boolean,
    teamId: Int?,
    isObserver: Boolean,
    isAlive: Boolean,
    isComplete: Boolean,
    isPlaceholder: Boolean,
    isVirtual: Boolean,
    isAttached: Boolean = false,
): Boolean = activeMatch && !replay && teamId != null && !isObserver && isAlive && isComplete &&
        !isPlaceholder && !isVirtual && !isAttached

/** Counts completed, living, real units and buildings by current team at match end.
 * Transported units count as owned survivors; generated attached subunits do not count separately.
 */
internal fun countMatchHistoryRemainingUnits(
    units: Iterable<MatchHistoryUnitSnapshot>,
): Map<Int, MatchHistoryRemainingCounts> {
    val counts = mutableMapOf<Int, MatchHistoryRemainingCounts>()
    for ((teamId1, isAlive, isComplete, isBuilding, isPlaceholder, isVirtual, isAttached) in units) {
        val teamId = teamId1 ?: continue
        if (!isAlive || !isComplete || isPlaceholder || isVirtual || isAttached) continue
        val old = counts[teamId] ?: MatchHistoryRemainingCounts()
        counts[teamId] = if (isBuilding) {
            old.copy(buildings = old.buildings + 1)
        } else {
            old.copy(units = old.units + 1)
        }
    }
    return counts
}

/** Native unit K/D treats experimental units as units and excludes the separate building bucket. */
internal fun matchHistoryUnitKillDeathRatio(
    unitsKilled: Int,
    experimentalsKilled: Int,
    unitsLost: Int,
    experimentalsLost: Int,
): Double? {
    val losses = unitsLost.coerceAtLeast(0) + experimentalsLost.coerceAtLeast(0)
    if (losses == 0) return null
    val kills = unitsKilled.coerceAtLeast(0) + experimentalsKilled.coerceAtLeast(0)
    return kills.toDouble() / losses
}

/** Small view state shared by the match-history screen and its policy tests. */
internal data class MatchHistoryPerformanceDisclosure(
    val expandedTeamIds: Set<Int> = emptySet(),
) {
    fun isExpanded(teamId: Int): Boolean = teamId in expandedTeamIds

    fun toggle(teamId: Int): MatchHistoryPerformanceDisclosure = copy(
        expandedTeamIds = if (isExpanded(teamId)) expandedTeamIds - teamId else expandedTeamIds + teamId,
    )
}

private fun identitySet(): MutableSet<Any> =
    Collections.newSetFromMap(IdentityHashMap())
