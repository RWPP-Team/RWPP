/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.StatsHandlerHistoryType
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardColumn
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.*
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import io.github.rwpp.config.*
import io.github.rwpp.game.nativeaccess.NativeReplayFileNameAccess
import org.koin.core.annotation.Single
import java.util.*
import com.corrodinggames.rts.game.units.Unit as NativeUnit

/** Captures every non-replay game session and keeps campaign mission progress in the same store. */
@Single(binds = [Initialization::class])
class MatchHistoryTracker(
    private val game: Game,
    private val history: MatchHistory,
    private val configIO: ConfigIO,
) : Initialization {
    private val lock = Any()
    private val matchStartGeneration = MatchStartGenerationGate()
    private var activeMatchId: String? = null
    private var startingParticipantIds: Set<Int> = emptySet()
    private val productionCounter = MatchHistoryProductionCounter()
    private val nativeResultMonitor = Runnable { pollNativeResult() }

    /** Current tracked session, exposed for diagnostics and programmatic status checks. */
    val currentMatch: MatchHistoryEntry?
        get() = synchronized(lock) { activeMatchId?.let(history::match) }

    override fun init() {
        if (MatchHistory.recoverInterrupted(history, System.currentTimeMillis())) saveHistory()
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        GameMainDispatcher.addUpdateTask(nativeResultMonitor)
        owner.own(AutoCloseable { GameMainDispatcher.removeUpdateTask(nativeResultMonitor) })
        GlobalEventChannel.filter(MapLoadEvent::class).subscribeAlways(owner) { event ->
            if (event.phase == MapLoadPhase.LOADED) synchronized(lock) { matchStartGeneration.onMapLoaded() }
        }
        GlobalEventChannel.filter(StartGameEvent::class).subscribeAlways(owner) { onMatchStarted() }
        GlobalEventChannel.filter(UnitCreatedEvent::class).subscribeAlways(owner) { event ->
            onUnitCreated(event)
        }
        GlobalEventChannel.filter(GameOverEvent::class).subscribeAlways(owner) { event ->
            finish(event.winTeam, event.gameTime.toLong())
        }
        // QuitGame is published before the native menu-return cleanup tears down room state.
        GlobalEventChannel.filter(QuitGameEvent::class).subscribeAlways(owner) { finishFromNativeState() }
        GlobalEventChannel.filter(ReturnMainMenuEvent::class).subscribeAlways(owner) { finishFromNativeState() }
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(owner) { finishFromNativeState() }
        GlobalEventChannel.filter(RoomReturnedEvent::class).subscribeAlways(owner) { finishFromNativeState() }
    }

    private fun onMatchStarted() = synchronized(lock) {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return@synchronized
        val mapPath = engine.currentMapPath?.takeIf(String::isNotBlank)
            ?: engine.networkEngine.currentGameMapPath?.takeIf(String::isNotBlank) ?: return@synchronized
        val normalizedPath = normalizeHistoryMapPath(mapPath)
        if (!matchStartGeneration.claimStart()) return@synchronized
        val active = activeMatchId?.let(history::match)
        if (active != null) {
            // A distinct native load can restart the same map; finish the old entry before opening it.
            complete(active.id, MatchOutcome.ABANDONED, 0L, emptyList())
        }
        if (game.state.replay) return@synchronized

        val campaignMap = isCampaignMap(normalizedPath)
        val matchType = when {
            game.state.multiplayer -> MatchType.MULTIPLAYER
            game.state.sandbox -> MatchType.SANDBOX
            campaignMap -> MatchType.CAMPAIGN
            else -> MatchType.SKIRMISH
        }
        // The campaign engine player is authoritative; network.localTeam can still refer to a
        // previously joined room until native disconnect finishes.
        val localTeam = if (campaignMap) engine.playerTeam ?: engine.networkEngine.localTeam
        else engine.networkEngine.localTeam ?: engine.playerTeam
        // Campaign maps allocate empty AI slots as placeholders. Include actual map teams, not
        // every slot in the native team array; network rooms retain their complete player roster.
        val populatedTeamIds = nativePopulatedTeamIds()
        val participants = game.gameRoom.getPlayers().filter {
            matchType != MatchType.CAMPAIGN || it.teamId in populatedTeamIds || it === localTeam
        }
        startingParticipantIds = participants.mapTo(hashSetOf()) { it.teamId }
        val entry = MatchHistoryEntry(
            id = UUID.randomUUID().toString(),
            startedAtMillis = System.currentTimeMillis(),
            mapPath = mapPath.replace('\\', '/'),
            matchType = matchType,
            difficulty = if (matchType == MatchType.CAMPAIGN) engine.settings.aiDifficulty
            else game.gameRoom.aiDifficulty,
            modeIds = game.gameRoom.gameModes.effectiveModes.map { it.id },
            playerNames = participants.map { it.name.orEmpty() }.filter(String::isNotBlank),
            localTeamId = localTeam?.teamId,
            localAllyTeam = localTeam?.allyTeam,
            localIsObserver = localTeam?.isSpectator == true,
        )
        history.recordMatchStarted(entry)
        activeMatchId = entry.id
        productionCounter.begin(NativeUnit.unitList.filterIsInstance<NativeUnit>())
        associateActiveReplayFile(engine)
        saveHistory()
    }

    /** Records only the native writer's exact filename; no map/date guess is made for new rows. */
    private fun associateActiveReplayFile(engine: GameEngine) {
        val entry = activeMatchId?.let(history::match) ?: return
        if (entry.replayFileName != null || !engine.replayEngine.isRecordingReplay) return
        val name = (engine.replayEngine as? NativeReplayFileNameAccess)
            ?.rwppReplayFileName()
            ?.takeIf(::isSafeReplayFileName)
            ?: return
        if (history.associateReplayFile(entry.id, name)) saveHistory()
    }

    /** Native UnitCreated is published for completed construction and factory output. */
    private fun onUnitCreated(event: UnitCreatedEvent) = synchronized(lock) {
        val unit = event.unit
        val team = unit.team
        if (!shouldCountMatchHistoryProductionEvent(
                activeMatch = activeMatchId != null,
                replay = game.state.replay,
                teamId = team?.teamId,
                isObserver = team?.isSpectator == true,
                isAlive = !unit.dead,
                isComplete = unit.buildProgress >= 1.0F,
                isPlaceholder = unit.isPlaceholder,
                isVirtual = unit.isVirtualUnit,
                isAttached = unit.attachedToUnit != null,
            )
        ) return@synchronized
        team ?: return@synchronized
        productionCounter.recordCompleted(unit, team.teamId, unit.isBuilding)
    }

    private fun finish(winTeam: Int?, gameTimeMillis: Long) = synchronized(lock) {
        val entry = activeMatchId?.let(history::match) ?: return@synchronized
        runCatching { GameEngine.getInstance() }.getOrNull()?.let(::associateActiveReplayFile)
        val outcome = matchOutcomeForGameOver(entry, winTeam) ?: return@synchronized
        complete(
            entry.id,
            outcome,
            gameTimeMillis,
            capturePlayerStats(gameTimeMillis),
        )
    }

    private fun finishFromNativeState() = synchronized(lock) {
        val entry = activeMatchId?.let(history::match) ?: return@synchronized
        val engine = runCatching { GameEngine.getInstance() }.getOrNull()
        engine?.let(::associateActiveReplayFile)
        val outcome = matchOutcomeFromNativeFlags(
            victoryShown = engine?.victoryScreenShown == true,
            defeatShown = engine?.defeatScreenShown == true,
            localIsObserver = entry.localIsObserver,
        ) ?: MatchOutcome.ABANDONED
        val duration = engine?.gameTimeMillis?.toLong() ?: 0L
        complete(entry.id, outcome, duration, capturePlayerStats(duration))
    }

    private fun pollNativeResult() = synchronized(lock) {
        val entry = activeMatchId?.let(history::match) ?: return@synchronized
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return@synchronized
        associateActiveReplayFile(engine)
        if (game.state.replay) return@synchronized
        val outcome = matchOutcomeFromNativeFlags(
            victoryShown = engine.victoryScreenShown,
            defeatShown = engine.defeatScreenShown,
            localIsObserver = entry.localIsObserver,
        ) ?: return@synchronized
        val duration = engine.gameTimeMillis.toLong()
        complete(entry.id, outcome, duration, capturePlayerStats(duration))
    }

    private fun complete(
        id: String,
        outcome: MatchOutcome,
        gameTimeMillis: Long,
        playerStats: List<MatchPlayerStats>,
    ) {
        history.recordMatchFinished(
            id = id,
            outcome = outcome,
            durationMillis = gameTimeMillis,
            finishedAtMillis = System.currentTimeMillis(),
            playerStats = playerStats,
        )
        if (activeMatchId == id) {
            activeMatchId = null
            productionCounter.reset()
        }
        saveHistory()
    }

    private fun capturePlayerStats(gameTimeMillis: Long): List<MatchPlayerStats> {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return emptyList()
        val campaign = activeMatchId?.let(history::match)?.matchType == MatchType.CAMPAIGN
        val populatedTeamIds = nativePopulatedTeamIds()
        return runCatching {
            val remainingCounts = countMatchHistoryRemainingUnits(
                NativeUnit.unitList.asSequence()
                    .filterIsInstance<NativeUnit>()
                    .map { unit ->
                        MatchHistoryUnitSnapshot(
                            teamId = unit.team?.teamId,
                            isAlive = !unit.dead,
                            isComplete = unit.buildProgress >= 1.0F,
                            isBuilding = unit.isBuilding,
                            isPlaceholder = unit.isPlaceholder,
                            isVirtual = unit.isVirtualUnit,
                            isAttached = unit.attachedToUnit != null,
                            isTransported = unit.transportedBy != null,
                        )
                    }
                    .asIterable(),
            )
            game.gameRoom.getPlayers().mapNotNull { team ->
                val nativeStats = runCatching { engine.statsHandler.getTeamStats(team) }.getOrNull()
                val hadCombat = nativeStats?.let {
                    it.unitsKilled + it.buildingsKilled + it.experimentalsKilled +
                            it.unitsLost + it.buildingsLost + it.experimentalsLost > 0
                } == true
                if (campaign && team.teamId !in startingParticipantIds &&
                    team.teamId !in populatedTeamIds && !hadCombat
                ) return@mapNotNull null
                val income = runCatching {
                    nativeStats?.history
                        ?.takeIf { it.hasHistory() }
                        ?.getValueAtTime(
                            StatsHandlerHistoryType.income,
                            gameTimeMillis.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        ) ?: LeaderboardColumn.income.getValueForTeam(team)
                }.getOrNull()
                MatchPlayerStats(
                    teamId = team.teamId,
                    allyTeam = team.allyTeam,
                    name = team.name.orEmpty(),
                    isAI = team.isAI,
                    isObserver = team.isSpectator,
                    credits = team.credits,
                    incomeRate = income,
                    unitsKilled = nativeStats?.unitsKilled ?: 0,
                    buildingsKilled = nativeStats?.buildingsKilled ?: 0,
                    experimentalsKilled = nativeStats?.experimentalsKilled ?: 0,
                    unitsLost = nativeStats?.unitsLost ?: 0,
                    buildingsLost = nativeStats?.buildingsLost ?: 0,
                    experimentalsLost = nativeStats?.experimentalsLost ?: 0,
                    unitsBuilt = if (team.isSpectator) null
                    else productionCounter.countsFor(team.teamId)?.units,
                    buildingsBuilt = if (team.isSpectator) null
                    else productionCounter.countsFor(team.teamId)?.buildings,
                    unitsRemaining = if (team.isSpectator) null
                    else remainingCounts[team.teamId]?.units ?: 0,
                    buildingsRemaining = if (team.isSpectator) null
                    else remainingCounts[team.teamId]?.buildings ?: 0,
                )
            }
        }.getOrElse {
            logger().warn("Could not capture native player statistics", it)
            emptyList()
        }
    }

    private fun nativePopulatedTeamIds(): Set<Int> = NativeUnit.unitList.asSequence()
        .filterIsInstance<NativeUnit>()
        .mapNotNull { it.team?.teamId }
        .toSet()

    private fun saveHistory() {
        runCatching { configIO.saveConfig(history) }
            .onFailure { logger().error("Could not save match history", it) }
    }

    private fun isCampaignMap(path: String): Boolean =
        path.startsWith("maps/normal/") || path.startsWith("maps/challenge/") || path.startsWith("maps/survival/")
}

internal fun matchOutcomeForWinTeam(
    winTeam: Int?,
    localAllyTeam: Int?,
    localIsObserver: Boolean,
): MatchOutcome = when {
    localIsObserver -> MatchOutcome.OBSERVED
    winTeam == null || localAllyTeam == null -> MatchOutcome.UNKNOWN
    localAllyTeam == winTeam -> MatchOutcome.WIN
    else -> MatchOutcome.LOSS
}

/** Campaign objectives have their own native victory/defeat flags; surviving-team state is insufficient. */
internal fun matchOutcomeForGameOver(entry: MatchHistoryEntry, winTeam: Int?): MatchOutcome? =
    if (entry.matchType == MatchType.CAMPAIGN) null
    else matchOutcomeForWinTeam(winTeam, entry.localAllyTeam, entry.localIsObserver)

internal fun matchOutcomeFromNativeFlags(
    victoryShown: Boolean,
    defeatShown: Boolean,
    localIsObserver: Boolean,
): MatchOutcome? {
    if (!victoryShown && !defeatShown) return null
    return when {
        localIsObserver -> MatchOutcome.OBSERVED
        victoryShown -> MatchOutcome.WIN
        else -> MatchOutcome.LOSS
    }
}

/** One StartGame notification is accepted per successful native map load, even for same-path restarts. */
internal class MatchStartGenerationGate {
    private var generation = 0L
    private var consumedGeneration: Long? = null

    fun onMapLoaded() {
        generation++
    }

    fun claimStart(): Boolean {
        if (consumedGeneration == generation) return false
        consumedGeneration = generation
        return true
    }
}
