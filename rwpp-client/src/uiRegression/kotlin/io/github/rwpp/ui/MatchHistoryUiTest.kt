package io.github.rwpp.ui

import io.github.rwpp.config.MatchPlayerStats
import io.github.rwpp.config.Settings
import java.text.NumberFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchHistoryUiTest {
    @Test
    fun unitKdIncludesExperimentalUnitsButExcludesBuildings() {
        val stats = stats(
            unitsKilled = 5,
            experimentalsKilled = 3,
            buildingsKilled = 900,
            unitsLost = 2,
            experimentalsLost = 1,
            buildingsLost = 600,
        )

        assertEquals(MatchHistoryUnitKdCounts(kills = 8, losses = 3), matchHistoryUnitKdCounts(stats))
        val rendered = formatMatchHistoryUnitKd(stats)
        val numeric = NumberFormat.getNumberInstance().parse(rendered)?.toDouble()
        assertTrue(numeric != null)
        assertEquals(8.0 / 3.0, numeric!!, 0.01)
    }

    @Test
    fun unitKdUsesInfinityOnlyForPositiveKillsWithoutLosses() {
        assertEquals("—", formatMatchHistoryUnitKd(stats(unitsKilled = 0, unitsLost = 0)))
        assertEquals("∞", formatMatchHistoryUnitKd(stats(unitsKilled = 4, unitsLost = 0)))
        val rendered = formatMatchHistoryUnitKd(stats(unitsKilled = 3, unitsLost = 2))
        val numeric = NumberFormat.getNumberInstance().parse(rendered)?.toDouble()
        assertTrue(numeric != null)
        assertEquals(1.5, numeric!!, 0.001)
    }

    @Test
    fun missingLegacyCountsAreMarkedUnavailableAndRecordedZeroStaysZero() {
        assertEquals("Not recorded", formatMatchHistoryCount(null, "Not recorded"))
        assertEquals("0", formatMatchHistoryCount(0, "Not recorded"))
        assertEquals("7", formatMatchHistoryCount(7, "Not recorded"))
    }

    @Test
    fun retentionChoicesIncludeThirtyDayDefaultAndUnlimited() {
        assertEquals(30, Settings().matchHistoryRetentionDays)
        assertEquals(listOf(7, 30, 90, 365, 0), matchHistoryRetentionOptions)
        assertEquals("rwpp.matchHistory.retention.30days", matchHistoryRetentionOptionKey(30))
        assertEquals("rwpp.matchHistory.retention.unlimited", matchHistoryRetentionOptionKey(0))
        assertTrue(matchHistoryRetentionOptions.all { it >= 0 })
    }

    private fun stats(
        unitsKilled: Int = 0,
        experimentalsKilled: Int = 0,
        buildingsKilled: Int = 0,
        unitsLost: Int = 0,
        experimentalsLost: Int = 0,
        buildingsLost: Int = 0,
    ) = MatchPlayerStats(
        teamId = 1,
        allyTeam = 1,
        name = "Player",
        isAI = false,
        isObserver = false,
        credits = 0.0,
        incomeRate = null,
        unitsKilled = unitsKilled,
        buildingsKilled = buildingsKilled,
        experimentalsKilled = experimentalsKilled,
        unitsLost = unitsLost,
        buildingsLost = buildingsLost,
        experimentalsLost = experimentalsLost,
    )
}
