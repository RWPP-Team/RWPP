package io.github.rwpp.ui

import androidx.compose.ui.graphics.Color
import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.hud.hudActionUnlocked
import io.github.rwpp.game.room.AeroPlayerAction
import io.github.rwpp.game.room.groupAeroPlayers
import io.github.rwpp.game.room.performAeroPlayerAction
import io.github.rwpp.game.room.snapshotAeroPlayers
import kotlin.test.*

class HudRulesAndPlayersTest {
    @Test
    fun resourceDimmingRemainsIndependentOfClickPermissionAndInvalidatesTheCanvas() {
        val button = CommandButton("build", enabled = true, dimmed = false)
        assertFalse(
            sameCommandVisuals(
                CommandFrame(buttons = listOf(button)),
                CommandFrame(buttons = listOf(button.copy(dimmed = true)))
            )
        )
        assertTrue(button.copy(dimmed = true).enabled, "Insufficient funds still allow entering placement")
    }

    @Test
    fun triggerMessagesPreserveColorThroughRevealBoundariesAndRetainLineBreaks() {
        val line = hudChatLine("", "Map\nmessage", -1, null, "", false, false, 0xffff3300.toInt())
        assertEquals("", hudTypedText(line, -20, 100).text)
        assertEquals("M", hudTypedText(line, 100, 100).text)
        val revealed = hudTypedText(line, 400, 100)
        assertEquals("Map\n", revealed.text)
        assertEquals(Color(0xffff3300), revealed.spanStyles.single().item.color)
        assertEquals(line, hudTypedText(line, Long.MAX_VALUE, 100))
        assertSame(line, hudTypedText(line, 0, -1))
    }

    @Test
    fun nativeNukeLocksAndLiveRosterIdentityAreRespected() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previous = engineField.get(null)
        val engine =
            unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java) as com.corrodinggames.rts.game.RWGameEngine
        engineField.set(null, engine)
        val teamArray = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }
        val original = teamArray.get(null)
        try {
            engine.networkEngine =
                unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.NetworkEngine::class.java) as com.corrodinggames.rts.gameFramework.network.NetworkEngine
            engine.networkEngine.gameSetup = com.corrodinggames.rts.gameFramework.network.GameSetup()
            engine.networkEngine.networked = true
            engine.networkEngine.isServer = true
            engine.replayEngine =
                unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.ReplayEngine::class.java) as com.corrodinggames.rts.gameFramework.ReplayEngine
            val builder =
                unsafe.allocateInstance(com.corrodinggames.rts.game.units.land.Builder::class.java) as com.corrodinggames.rts.game.units.OrderableUnit
            val nuke =
                com.corrodinggames.rts.game.units.actions.SpecialActionBuild(com.corrodinggames.rts.game.units.UnitType.NukeLaucher)
            engine.networkEngine.gameSetup.noNukes = true
            assertFalse(
                hudActionUnlocked(nuke, builder),
                "Native no-nukes lock must precede placement/payment handling"
            )
            engine.networkEngine.gameSetup.noNukes = false
            assertTrue(hudActionUnlocked(nuke, builder))
            fun player(slot: Int, ally: Int) = (unsafe.allocateInstance(Player::class.java) as Player).apply {
                teamId = slot; allyTeam = ally; name = "same-name"
            }

            val first = player(0, 2);
            val second = player(1, 2);
            val observer = player(2, -3)
            val teams =
                arrayOfNulls<Team>(Team.totalTeamSlots).apply { this[0] = first; this[1] = second; this[2] = observer }
            teamArray.set(null, teams)
            engine.networkEngine.localTeam = first
            val before = snapshotAeroPlayers(engine)
            assertEquals(listOf(2, -3), groupAeroPlayers(before.rows).map { it.first })
            assertEquals(2, groupAeroPlayers(before.rows).first().second.size)
            second.allyTeam = 3; second.name = "changed"
            val after = snapshotAeroPlayers(engine)
            assertEquals(listOf(2, 3, -3), groupAeroPlayers(after.rows).map { it.first })
            assertEquals("changed", after.rows[1].name)
            assertEquals("same-name", before.rows[1].name, "Rows are immutable snapshots")
            assertFalse(performAeroPlayerAction(engine, first, AeroPlayerAction.KICK), "Host cannot kick self")
            teams[1] = player(1, 3)
            assertFalse(
                performAeroPlayerAction(engine, second, AeroPlayerAction.KICK),
                "A stale row must never target a replacement"
            )
            engine.networkEngine.isServer = false
            assertFalse(performAeroPlayerAction(engine, teams[1]!!, AeroPlayerAction.KICK))
        } finally {
            teamArray.set(null, original)
            engineField.set(null, previous)
        }
    }
}
