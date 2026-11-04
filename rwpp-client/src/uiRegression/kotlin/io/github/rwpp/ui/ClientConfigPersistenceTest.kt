package io.github.rwpp.ui

import io.github.rwpp.api.config.Config
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.config.ConfigIOImpl
import io.github.rwpp.config.*
import io.github.rwpp.logic.LogicModule
import io.github.rwpp.logic.ProductInitialization
import io.github.rwpp.logic.config.ConfigModule
import io.github.rwpp.logic.config.registerClientConfigSerializers
import io.github.rwpp.logic.hud.initializeDefaultHud
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.test.*

class ClientConfigPersistenceTest {
    // Load the production generated module without asking Koin's test compiler to validate
    // unrelated game services that are deliberately not instantiated by this persistence test.
    private fun configurationModule(): org.koin.core.module.Module =
        Class.forName("io.github.rwpp.logic.config.IoGithubRwppLogicConfigConfigModuleModuleKt")
            .getMethod("module", ConfigModule::class.java).invoke(null, ConfigModule()) as org.koin.core.module.Module

    private fun logicModule(): org.koin.core.module.Module =
        Class.forName("io.github.rwpp.logic.IoGithubRwppLogicLogicModuleModuleKt")
            .getMethod("module", LogicModule::class.java).invoke(null, LogicModule()) as org.koin.core.module.Module

    @Test
    fun freshContainerRestoresActualClientSettingsAndHudWithoutReplacingSingletons() {
        val previous = System.getProperty("user.dir")
        val directory = Files.createTempDirectory("rwpp-client-cold-config")
        try {
            System.setProperty("user.dir", directory.toString())
            registerClientConfigSerializers()
            val writer = ConfigIOImpl()
            val first = koinApplication { modules(configurationModule(), logicModule()) }
            try {
                assertNotNull(first.koin.get<ProductInitialization>())
                val configs = first.koin.getAll<Config>()
                assertEquals(7, configs.size, "Every client configuration must be bound as Config")
                assertTrue(configs.any { it is MatchHistory }, "Match history must be persisted with client configs")
                first.koin.get<Settings>().apply {
                    improvedHealthBar = true; enhancedWorldRendering = true; aeroRangeUseTeamColors = false
                    hideOriginalHud = false; showExtraButton = true; hudRefreshRate = 45
                }
                first.koin.get<HudComponentDataList>().defaultsInitialized = true
                configs.forEach(writer::saveConfig)
            } finally {
                first.close()
            }
            val fresh = koinApplication { modules(configurationModule(), logicModule()) }
            try {
                val target = fresh.koin.get<Settings>()
                val reader = object : ConfigIO by writer {
                    override fun getKoin() = fresh.koin
                    override fun readAllConfig() = super<ConfigIO>.readAllConfig()
                }
                reader.readAllConfig()
                assertSame(target, fresh.koin.get<Settings>())
                assertTrue(target.improvedHealthBar); assertTrue(target.enhancedWorldRendering)
                assertFalse(target.hideOriginalHud); assertTrue(target.showExtraButton)
                assertFalse(target.aeroRangeUseTeamColors)
                assertEquals(45, target.hudRefreshRate)
                val hud = fresh.koin.get<HudComponentDataList>()
                assertTrue(hud.defaultsInitialized)
                initializeDefaultHud(hud)
                assertFalse(target.hideOriginalHud, "Engine entry must preserve the saved native HUD preference")
            } finally {
                fresh.close()
            }
        } finally {
            System.setProperty("user.dir", previous)
        }
    }

    @Test
    fun matchHistoryRoundTripsNestedPlayerStatisticsAndUnfinishedSessions() {
        val previous = System.getProperty("user.dir")
        val directory = Files.createTempDirectory("rwpp-match-history-roundtrip")
        try {
            System.setProperty("user.dir", directory.toString())
            registerClientConfigSerializers()
            val io = ConfigIOImpl()
            val history = MatchHistory()
            val entry = MatchHistoryEntry(
                id = "campaign-win", startedAtMillis = 10,
                mapPath = "maps/normal/mission.tmx", matchType = MatchType.CAMPAIGN,
                difficulty = 1, modeIds = listOf("rwpp:campaign"),
                playerNames = listOf("Player"), localTeamId = 0, localAllyTeam = 0,
                replayFileName = "Mission (2 Jun 2026 10.30.00).replay",
            )
            history.recordMatchStarted(entry)
            history.recordMatchFinished(
                entry.id, MatchOutcome.WIN, 2000, 30, listOf(
                    MatchPlayerStats(
                        teamId = 0, allyTeam = 0, name = "Player", isAI = false, isObserver = false,
                        credits = 1234.5, incomeRate = null,
                        unitsKilled = 3, buildingsKilled = 2, experimentalsKilled = 1,
                        unitsLost = 1, buildingsLost = 0, experimentalsLost = 0,
                    ),
                )
            )
            history.recordMatchStarted(entry.copy(id = "unfinished", matchType = MatchType.SKIRMISH))
            io.saveConfig(history)
            val restored = assertNotNull(io.readConfig(MatchHistory::class))
            assertEquals(
                history,
                restored,
                "The actual config codec must preserve nested statistics and nullable fields"
            )
            assertEquals(entry.replayFileName, restored.match(entry.id)?.replayFileName)
            assertTrue(MatchHistory.recoverInterrupted(restored, 50))
            assertEquals(MatchOutcome.ABANDONED, restored.match("unfinished")?.outcome)
            assertEquals(1, restored.campaignProgress(entry.mapPath)?.wins)
        } finally {
            System.setProperty("user.dir", previous)
        }
    }

}
