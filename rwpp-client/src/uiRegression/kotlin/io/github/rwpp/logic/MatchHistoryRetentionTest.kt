package io.github.rwpp.logic

import io.github.rwpp.api.config.Config
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.game.Game
import io.github.rwpp.config.*
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.*

class MatchHistoryRetentionTest {
    private val day = 86_400_000L
    private val now = 100L * day
    private fun entry(id: String, ageDays: Int) = MatchHistoryEntry(
        id, now - ageDays * day, "maps/normal/mission.tmx", MatchType.CAMPAIGN,
        0, emptyList(), outcome = MatchOutcome.WIN,
    )

    @Test
    fun expiresOnlyDetailsBeyondBoundaryAndProtectsCurrentMatch() {
        val history = MatchHistory()
        history.matches.addAll(
            listOf(
                entry("old", 31), entry("boundary", 30),
                entry("recent", 1), entry("current", 40), entry("future", -1)
            )
        )
        history.campaignMissions.add(CampaignMissionProgress("maps/normal/mission.tmx", attempts = 8, wins = 4))
        assertEquals(1, history.pruneMatches(now, 30, "current"))
        assertEquals(listOf("boundary", "recent", "current", "future"), history.allMatches().map { it.id })
        assertEquals(4, history.campaignMissions.single().wins)
        assertEquals(0, history.pruneMatches(now, 30, "current"))
    }

    @Test
    fun unlimitedKeepsOldRecordsAndLegacyDefaultsStayCompatible() {
        val history = MatchHistory(matches = mutableListOf(entry("old", 90)))
        assertEquals(0, history.pruneMatches(now, 0))
        assertEquals(1, history.allMatches().size)
        assertEquals(30, Settings().matchHistoryRetentionDays)
        val legacy = MatchPlayerStats(0, 0, "Player", false, false, 0.0, null, 0, 0, 0, 0, 0, 0)
        assertNull(legacy.unitsBuilt)
        assertNull(legacy.buildingsBuilt)
        assertNull(legacy.unitsRemaining)
    }

    @Test
    fun periodicCleanupWritesOnlyOnChangeAndRestoresMemoryOnWriteFailure() {
        val history = MatchHistory(matches = mutableListOf(entry("old", 31), entry("recent", 1)))
        val io = MemoryIO()
        val maintenance = maintenance(history, Settings(), io)
        assertEquals(1, maintenance.pruneExpired(now).getOrThrow())
        assertEquals(1, io.writes.size)
        assertEquals(0, maintenance.pruneExpired(now).getOrThrow())
        assertEquals(1, io.writes.size)
        history.matches.add(entry("failed", 40))
        io.failHistory = true
        assertTrue(maintenance.pruneExpired(now).isFailure)
        assertNotNull(history.match("failed"))
    }

    @Test
    fun settingsFailureDoesNotDeleteAndSuccessfulChangePersistsNewPeriod() {
        val history = MatchHistory(matches = mutableListOf(entry("old", 40)))
        val settings = Settings(matchHistoryRetentionDays = 0)
        val io = MemoryIO()
        val maintenance = maintenance(history, settings, io)
        io.failSettings = true
        assertTrue(maintenance.updateRetention(30).isFailure)
        assertEquals(0, settings.matchHistoryRetentionDays)
        assertEquals(1, history.allMatches().size)
        io.failSettings = false
        io.failHistory = true
        assertTrue(maintenance.updateRetention(30).isFailure)
        assertEquals(0, settings.matchHistoryRetentionDays)
        assertEquals(1, history.allMatches().size)
        io.failHistory = false
        assertEquals(1, maintenance.updateRetention(30).getOrThrow())
        assertEquals(30, settings.matchHistoryRetentionDays)
        assertTrue(history.allMatches().isEmpty())
    }

    private fun maintenance(history: MatchHistory, settings: Settings, io: ConfigIO): MatchHistoryMaintenance {
        val game = Proxy.newProxyInstance(Game::class.java.classLoader, arrayOf(Game::class.java)) { _, method, _ ->
            error("Unexpected game access: ${method.name}")
        } as Game
        return MatchHistoryMaintenance(history, settings, MatchHistoryTracker(game, history, io), io)
    }

    private class MemoryIO : ConfigIO {
        val writes = mutableListOf<Config>()
        var failHistory = false
        var failSettings = false
        override fun saveConfig(config: Config) {
            if (config is MatchHistory && failHistory || config is Settings && failSettings) error("write failed")
            writes += config
        }

        override fun <T : Config> readConfig(clazz: KClass<T>): T? = null
        override fun <T : Config> deleteConfig(clazz: KClass<T>) = Unit
        override fun saveSingleConfig(group: String, key: String, value: Any?) = Unit
        override fun readSingleConfig(group: String, key: String): String? = null
        override fun <T> getGameConfig(name: String): T = error("unused")
        override fun setGameConfig(name: String, value: Any?) = Unit
    }
}
