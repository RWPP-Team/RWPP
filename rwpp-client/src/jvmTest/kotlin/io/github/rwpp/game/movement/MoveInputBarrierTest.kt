package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import kotlin.test.*

class MoveInputBarrierTest {
    private fun move(x: Float, queued: Boolean = false) = CommandControllerCommand(null).apply {
        setMoveWaypoint(x, 100f)
        queueOrder = queued
        cancelDuplicateWaypoint = false
    }

    private fun applied(command: CommandControllerCommand) = Waypoint().apply { copyFrom(command.waypoint) }

    @Test
    fun sendingAnInputDoesNotAcknowledgeItsApplication() {
        val gate = MoveInputBarrier()
        val input = move(200f)
        val receipt = gate.register(input)
        assertFalse(receipt.received)
        assertFalse(receipt.applied)
        assertSame(receipt, gate.acknowledge(input, applied(input)))
        assertTrue(receipt.applied)
        assertTrue(gate.empty)
    }

    @Test
    fun earlierIdenticalAutomaticOrderCannotAcknowledgeALaterUserInput() {
        val gate = MoveInputBarrier()
        val automatic = move(200f, true)
        val user = move(200f, true)
        val oldReceipt = gate.register(automatic)
        val userReceipt = gate.register(user)
        assertSame(oldReceipt, gate.acknowledge(automatic, applied(automatic)))
        assertFalse(userReceipt.received, "One native application consumes exactly one FIFO receipt")
        assertSame(userReceipt, gate.acknowledge(user, applied(user)))
        assertTrue(userReceipt.applied)
    }

    @Test
    fun mismatchedNativeOrderCannotReleaseAQueuedShiftOrNewReplacement() {
        val gate = MoveInputBarrier()
        val replacement = move(200f)
        val shift = move(300f, true)
        val first = gate.register(replacement)
        val second = gate.register(shift)
        assertNull(gate.acknowledge(shift, applied(shift)))
        assertFalse(first.received)
        assertFalse(second.received)
        assertTrue(gate.poisoned, "Native reordering falls back without ever reusing the unmatched FIFO")
        assertNull(gate.acknowledge(replacement, applied(replacement)))
        assertFalse(first.applied)
        assertFalse(second.applied)
    }

    @Test
    fun rejectedNativeWaypointDoesNotReleaseTheInputBarrier() {
        val gate = MoveInputBarrier()
        val command = move(200f)
        val receipt = gate.register(command)
        gate.acknowledge(command, move(100f).waypoint)
        assertTrue(receipt.received)
        assertFalse(receipt.applied)
    }

    @Test
    fun cancelingAnUnsentAutomaticBatchKeepsLaterUserReceiptInOrder() {
        val gate = MoveInputBarrier()
        val automatic = gate.register(move(100f))
        val userCommand = move(300f)
        val user = gate.register(userCommand)
        gate.discard(automatic)
        assertSame(user, gate.acknowledge(userCommand, applied(userCommand)))
        assertTrue(user.applied)
    }

    @Test
    fun excessiveUnconfirmedInputsRemainBoundedAndCannotRestartOnAnOldPacket() {
        val gate = MoveInputBarrier()
        repeat(129) { gate.register(move(it.toFloat())) }
        assertTrue(gate.poisoned)
        val later = move(1000f)
        val receipt = gate.register(later)
        assertTrue(receipt.received)
        assertFalse(receipt.applied)
        assertNull(gate.acknowledge(later, applied(later)))
        assertFalse(gate.empty)
    }

    @Test
    fun receiptMatchesRealNativeNetworkRoundTripWithoutDependingOnUnserializedMetadata() {
        val singleton = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previousEngine = singleton.get(null)
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        // Team's static neutral players construct dummy units and require a current native engine.
        singleton.set(null, unsafe.allocateInstance(RWGameEngine::class.java))
        var teams: Array<Team?>? = null
        var previous: Team? = null
        try {
            @Suppress("UNCHECKED_CAST")
            val nativeTeams = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }
                .get(null) as Array<Team?>
            teams = nativeTeams
            previous = nativeTeams[0]
            val team = (unsafe.allocateInstance(Player::class.java) as Player).apply { teamId = 0 }
            nativeTeams[0] = team
            val outgoing = move(200f, true).apply {
                this.team = team
                player = team
                source = "local input metadata is not serialized"
                createdGameTimeMillis = 1234
            }
            val gate = MoveInputBarrier()
            val receipt = gate.register(outgoing)
            val bytes = OutputNetStream().also(outgoing::write).toByteArray()
            val returned = CommandControllerCommand(null).apply { read(InputNetStream(bytes)) }
            assertEquals(MoveInputSignature.of(outgoing), MoveInputSignature.of(returned))
            assertSame(receipt, gate.acknowledge(returned, applied(returned)))
            assertTrue(receipt.applied)
        } finally {
            teams?.set(0, previous)
            singleton.set(null, previousEngine)
        }
    }
}
