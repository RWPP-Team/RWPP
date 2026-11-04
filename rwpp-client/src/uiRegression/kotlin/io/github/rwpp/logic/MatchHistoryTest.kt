package io.github.rwpp.logic

import io.github.rwpp.config.*
import kotlin.test.*

class MatchHistoryTest {
    @Test
    fun normalizesMapPathsWithoutCollapsingFolderIdentity() {
        assertEquals("maps/normal/zone/mission one", normalizeHistoryMapPath("\\maps\\normal\\zone\\Mission One.TMX"))
        assertEquals("maps/normal/zone/mission one", campaignMissionKey("maps/normal/zone/Mission One.tmx"))
        assertNull(campaignMissionKey("maps/skirmish/zone/Mission One.tmx"))
    }

    @Test
    fun duplicateStartAndEndEventsUpdateMissionProgressOnlyOnce() {
        val history = MatchHistory()
        val entry = campaignEntry("match-1")

        history.recordMatchStarted(entry)
        history.recordMatchStarted(entry)
        history.recordMatchFinished(entry.id, MatchOutcome.WIN, 12_000, 20)
        history.recordMatchFinished(entry.id, MatchOutcome.LOSS, 25_000, 30)

        assertEquals(1, history.allMatches().size)
        assertEquals(MatchOutcome.WIN, history.match(entry.id)?.outcome)
        val progress = history.campaignProgress(entry.mapPath)
        assertEquals(1, progress?.attempts)
        assertEquals(1, progress?.wins)
        assertEquals(0, progress?.losses)
        assertEquals(12_000L, progress?.bestTimeMillis)
    }

    @Test
    fun onlineGamesOnCampaignMapsDoNotCountAsCampaignCompletion() {
        val history = MatchHistory()
        val entry = campaignEntry("match-online").copy(matchType = MatchType.MULTIPLAYER)

        history.recordMatchStarted(entry)
        history.recordMatchFinished(entry.id, MatchOutcome.WIN, 4_000, 10)

        assertNull(history.campaignProgress(entry.mapPath))
    }

    @Test
    fun observerAndMissingTeamOutcomesAreNotRecordedAsLosses() {
        assertEquals(MatchOutcome.OBSERVED, matchOutcomeForWinTeam(1, 1, localIsObserver = true))
        assertEquals(MatchOutcome.UNKNOWN, matchOutcomeForWinTeam(1, null, localIsObserver = false))
        assertEquals(MatchOutcome.WIN, matchOutcomeForWinTeam(2, 2, localIsObserver = false))
        assertEquals(MatchOutcome.LOSS, matchOutcomeForWinTeam(3, 2, localIsObserver = false))
        assertEquals(MatchOutcome.WIN, matchOutcomeFromNativeFlags(true, false, localIsObserver = false))
        assertEquals(MatchOutcome.LOSS, matchOutcomeFromNativeFlags(false, true, localIsObserver = false))
        assertEquals(MatchOutcome.OBSERVED, matchOutcomeFromNativeFlags(true, false, localIsObserver = true))
        assertNull(matchOutcomeFromNativeFlags(false, false, localIsObserver = false))
    }

    @Test
    fun sameMapRestartIsASeparateSuccessfulMapLoadButDuplicateStartIsIgnored() {
        val gate = MatchStartGenerationGate()
        gate.onMapLoaded()
        assertTrue(gate.claimStart())
        assertFalse(gate.claimStart())

        // The same path can be loaded again for a retry; each successful native load gets a new generation.
        gate.onMapLoaded()
        assertTrue(gate.claimStart())
    }

    @Test
    fun campaignGameOverEventDoesNotOverrideNativeObjectiveFlags() {
        val campaign = campaignEntry("campaign")
        val skirmish = campaign.copy(matchType = MatchType.SKIRMISH)

        assertNull(matchOutcomeForGameOver(campaign, winTeam = 10))
        assertEquals(MatchOutcome.LOSS, matchOutcomeForGameOver(skirmish, winTeam = 10))
    }

    @Test
    fun legacyReplayAssociationNeedsUniqueMapAndStartTimeMatch() {
        val entry = campaignEntry("legacy").copy(
            mapPath = "maps/normal/[z;p10]Mission_One.TMX",
            startedAtMillis = 1_000_000,
            outcome = MatchOutcome.WIN,
        )
        val links = resolveLegacyMatchReplayLinks(
            entries = listOf(entry),
            candidates = listOf(
                MatchHistoryReplayCandidate("Mission One (one).replay", "Mission One", 1_020_000),
                MatchHistoryReplayCandidate("Other Map (one).replay", "Other Map", 1_000_000),
                MatchHistoryReplayCandidate("Mission One (old).replay", "Mission One", 1_300_000),
            ),
        )

        assertEquals(mapOf("legacy" to "Mission One (one).replay"), links)
        assertEquals(
            "mission one",
            replayAssociationMapName("[z;p10]Mission_One [v1.15] (2 Jun 2026 10.30.00).replay"),
        )
    }

    @Test
    fun legacyReplayAssociationRejectsAmbiguousReplayOrMatchCandidates() {
        val first = campaignEntry("first").copy(startedAtMillis = 1_000_000, outcome = MatchOutcome.WIN)
        val second = campaignEntry("second").copy(startedAtMillis = 1_000_000, outcome = MatchOutcome.LOSS)
        val oneReplay = listOf(MatchHistoryReplayCandidate("Mission One.replay", "Mission One", 1_000_000))

        assertTrue(
            resolveLegacyMatchReplayLinks(
                listOf(first), oneReplay + MatchHistoryReplayCandidate(
                    "Mission One (copy).replay", "Mission One", 1_030_000,
                )
            ).isEmpty()
        )
        assertTrue(resolveLegacyMatchReplayLinks(listOf(first, second), oneReplay).isEmpty())
    }

    @Test
    fun legacyReplayAssociationIgnoresManyUnrelatedMapNamesWithinTheTimeWindow() {
        val ended = campaignEntry("legacy-many").copy(
            mapPath = "maps/normal/Mission_One.tmx",
            startedAtMillis = 1_000_000,
            outcome = MatchOutcome.WIN,
        )
        val unrelated = (0 until 2_000).map { index ->
            MatchHistoryReplayCandidate("Other-$index.replay", "Other Map $index", 1_000_000L + index)
        }
        val expected = MatchHistoryReplayCandidate("Mission One (native).replay", "Mission One", 1_020_000)

        assertEquals(
            mapOf("legacy-many" to expected.fileName),
            resolveLegacyMatchReplayLinks(listOf(ended), unrelated + expected),
        )
    }

    @Test
    fun exactReplayAssociationCannotBeOverwrittenAndRejectsUnsafeNames() {
        val history = MatchHistory()
        val entry = campaignEntry("with-replay")
        history.recordMatchStarted(entry)

        assertTrue(history.associateReplayFile(entry.id, "Mission One.replay"))
        assertFalse(history.associateReplayFile(entry.id, "replacement.replay"))
        assertFalse(history.associateReplayFile(entry.id, "../outside.replay"))
        assertEquals("Mission One.replay", history.match(entry.id)?.replayFileName)
    }

    @Test
    fun duplicateExactReplayReferencesAreNotOfferedForPlaybackOrLegacyMatching() {
        val first = campaignEntry("exact-one").copy(replayFileName = "Mission One.replay")
        val second = campaignEntry("exact-two").copy(replayFileName = "Mission One.replay")
        val candidates = listOf(
            MatchHistoryReplayCandidate("Mission One.replay", "Mission One", 1),
        )

        assertTrue(resolveExactMatchReplayLinks(listOf(first, second), setOf("Mission One.replay")).isEmpty())
        assertTrue(resolveLegacyMatchReplayLinks(listOf(first, campaignEntry("legacy")), candidates).isEmpty())
        val inProgress = campaignEntry("active").copy(
            outcome = MatchOutcome.IN_PROGRESS,
            replayFileName = "Mission One.replay",
        )
        assertTrue(resolveExactMatchReplayLinks(listOf(inProgress), setOf("Mission One.replay")).isEmpty())
        assertTrue(resolveLegacyMatchReplayLinks(listOf(inProgress), candidates).isEmpty())
    }

    @Test
    fun reusedNativeReplayFilenameMovesAssociationToCurrentMatch() {
        val history = MatchHistory()
        val older = campaignEntry("older")
        val current = campaignEntry("current")
        history.recordMatchStarted(older)
        history.recordMatchStarted(current)
        assertTrue(history.associateReplayFile(older.id, "Mission One.replay"))

        assertTrue(history.associateReplayFile(current.id, "Mission One.replay"))
        assertNull(history.match(older.id)?.replayFileName)
        assertEquals("Mission One.replay", history.match(current.id)?.replayFileName)
    }

    @Test
    fun replayMustStillResolveToSameSafeFilenameAndParsedTimestamp() {
        assertTrue(matchHistoryReplayIdentityMatches("Mission.replay", "Mission.replay", 1_000, 1_000))
        assertFalse(matchHistoryReplayIdentityMatches("Mission.replay", "Renamed.replay", 1_000, 1_000))
        assertFalse(matchHistoryReplayIdentityMatches("Mission.replay", "Mission.replay", 1_000, 2_000))
        assertTrue(matchHistoryReplayIdentityMatches("Mission.replay", "Mission.replay", null, null))
        assertFalse(matchHistoryReplayIdentityMatches("../Mission.replay", "../Mission.replay", null, null))
    }

    private fun campaignEntry(id: String) = MatchHistoryEntry(
        id = id,
        startedAtMillis = 1,
        mapPath = "maps/normal/zone/Mission One.tmx",
        matchType = MatchType.CAMPAIGN,
        difficulty = 0,
        modeIds = emptyList(),
        localAllyTeam = 0,
    )
}
