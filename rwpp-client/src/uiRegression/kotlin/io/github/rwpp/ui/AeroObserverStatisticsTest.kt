package io.github.rwpp.ui

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.leaderboard.Leaderboard
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardColumn
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardGroupMode
import io.github.rwpp.game.hud.aeroStatisticsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroObserverStatisticsTest {
    @Test
    fun snapshotsExcludeObserversAggregateAlliesAndRefreshCreditsWithoutChangingOldRows() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = com.corrodinggames.rts.gameFramework.GameEngine::class.java.getDeclaredField("al")
            .apply { isAccessible = true }
        val previousEngine = engineField.get(null)
        val engine =
            unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java) as com.corrodinggames.rts.game.RWGameEngine
        engine.settings =
            unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.SettingsEngine::class.java) as com.corrodinggames.rts.gameFramework.SettingsEngine
        engineField.set(null, engine)
        val teamsField = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }
        val oldTeams = teamsField.get(null)
        fun player(slot: Int, ally: Int, amount: Double) =
            (unsafe.allocateInstance(Player::class.java) as Player).apply {
                teamId = slot; allyTeam = ally; name = "player-$slot"; credits = amount
            }

        val first = player(0, 1, 1200.0)
        val second = player(1, 1, 3400.0)
        val observer = player(2, -3, 99999.0)
        try {
            val teams = arrayOfNulls<Team>(Team.totalTeamSlots)
            teams[0] = first; teams[1] = second; teams[2] = observer
            teamsField.set(null, teams)
            val credits = LeaderboardColumn.credits
            val rows = aeroStatisticsSnapshot(credits, LeaderboardGroupMode.player)
            assertEquals(listOf("player-0", "player-1"), rows.map { it.name })
            assertEquals(Leaderboard.formatColumnValue(credits, 1200), rows.first().value)
            val grouped = aeroStatisticsSnapshot(credits, LeaderboardGroupMode.allyGroup)
            assertEquals(1, grouped.size)
            assertTrue(grouped.single().group)
            assertEquals(Leaderboard.formatColumnValue(credits, 4600), grouped.single().value)
            first.credits = 2300.0
            assertEquals(
                Leaderboard.formatColumnValue(credits, 2300),
                aeroStatisticsSnapshot(credits, LeaderboardGroupMode.player).first().value
            )
            assertEquals(
                Leaderboard.formatColumnValue(credits, 1200),
                rows.first().value,
                "Published snapshots must remain immutable"
            )
            assertEquals(3, aeroStatisticsSnapshot(credits, LeaderboardGroupMode.combinedPlayerAndGroup).size)
        } finally {
            teamsField.set(null, oldTeams)
            engineField.set(null, previousEngine)
        }
    }
}
