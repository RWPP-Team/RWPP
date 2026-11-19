package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.game.units.land.Tank
import com.corrodinggames.rts.gameFramework.CommandController
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import kotlin.test.*

class NativeMoveQueueTest {
    @Test
    fun snapshotsDoNotShareMutableWaypointsAndDetectDestinationChanges() = nativeFixture { _, _, unit ->
        unit.queueMoveWaypoint(80f, 120f).formationFaceEnemy = true
        unit.queueMoveWaypoint(300f, 320f)
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        assertNotSame(unit.currentWaypoint, expected.first())
        assertTrue(expected.first().formationFaceEnemy)
        assertTrue(NativeMoveQueue.sameQueue(unit, expected))
        unit.currentWaypoint.setMoveTo(80.01f, 120f)
        assertFalse(NativeMoveQueue.sameQueue(unit, expected), "A native approximately-matching target is still a different user order")
        assertEquals(80f, expected.first().targetX)
    }

    @Test
    fun nativeOutgoingBatchPreservesShiftAndBuildSuffixAndNetworkOrder() = nativeFixture { engine, _, unit ->
        unit.queueMoveWaypoint(80f, 120f).formationFaceEnemy = true
        unit.allocateWaypoint().setBuildTo(200f, 220f, UnitType.turret, 2)
        unit.queueMoveWaypoint(300f, 320f)
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        val commands = assertNotNull(submit(engine, unit, expected,
            listOf(MovePoint(40f, 60f), MovePoint(80f, 120f)), retainHead = false))

        assertEquals(4, commands.size)
        assertEquals(commands, engine.commandController.networkPendingCommands.toArray().toList())
        assertTrue(engine.commandController.localPendingCommands.isEmpty(), "Networked input must wait for native host scheduling")
        assertTrue(NativeMoveQueue.sameQueue(unit, expected), "Outgoing commands must not directly edit live unit waypoints")
        assertEquals(3, unit.waypointCount)
        assertEquals(13f, unit.x)
        assertEquals(50f, unit.y)
        commands.forEachIndexed { index, command ->
            assertSame(engine.playerTeam, command.team)
            assertSame(engine.playerTeam, command.player)
            assertEquals(1, command.unitCount)
            assertEquals(index != 0, command.queueOrder)
            assertTrue(command.pathfindingQueued, "Input-side prefetch must not let later queued commands overtake the replacement")
            assertTrue(command.allPathsReady())
            val restored = roundTrip(command)
            assertEquals(index != 0, restored.queueOrder)
            assertFalse(restored.cancelDuplicateWaypoint)
            assertFalse(restored.clearWaypointsFirst)
            assertFalse(restored.finishNonBuildWaypoints)
            assertFalse(restored.hasSystemAction)
            assertContentEquals(waypointBytes(command.waypoint), waypointBytes(restored.waypoint))
        }
        assertEquals(listOf(WaypointType.move, WaypointType.move, WaypointType.build, WaypointType.move),
            commands.map { it.waypoint.type })
        assertTrue(commands[1].waypoint.formationFaceEnemy)
        assertEquals(UnitType.turret, commands[2].waypoint.unitType)
        assertEquals(2, commands[2].waypoint.buildTechLevel)
        assertEquals(300f, commands[3].waypoint.targetX)
    }

    @Test
    fun buildApproachRetainsOriginalBuildAndDoesNotMoveToTheBuildingCenter() = nativeFixture { engine, _, unit ->
        unit.allocateWaypoint().setBuildTo(200f, 220f, UnitType.turret, 2)
        unit.queueMoveWaypoint(300f, 320f)
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        assertNull(submit(engine, unit, expected, listOf(MovePoint(0f, 0f)), retainHead = true))
        assertTrue(engine.commandController.networkPendingCommands.isEmpty())
        val approach = MovePoint(160f, 190f)
        val commands = assertNotNull(submit(engine, unit, expected, listOf(approach), retainHead = true))
        assertEquals(listOf(WaypointType.move, WaypointType.build, WaypointType.move), commands.map { it.waypoint.type })
        assertEquals(approach.x, commands.first().waypoint.targetX)
        assertEquals(approach.y, commands.first().waypoint.targetY)
        assertEquals(200f, commands[1].waypoint.targetX)
        assertEquals(220f, commands[1].waypoint.targetY)
        assertEquals(UnitType.turret, roundTrip(commands[1]).waypoint.unitType)
        assertEquals(2, roundTrip(commands[1]).waypoint.buildTechLevel)
        assertTrue(NativeMoveQueue.sameQueue(unit, expected))
    }

    @Test
    fun fullNativeQueueCannotLoseItsLastUserGoalToMakeRoom() = nativeFixture { engine, _, unit ->
        repeat(NativeMoveQueue.CAPACITY) { index -> unit.queueMoveWaypoint(80f + index, 120f) }
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        assertNull(submit(engine, unit, expected,
            listOf(MovePoint(40f, 60f), MovePoint(80f, 120f)), retainHead = false))
        assertTrue(engine.commandController.networkPendingCommands.isEmpty())
        assertEquals(NativeMoveQueue.CAPACITY, unit.waypointCount)
        val unchanged = assertNotNull(NativeMoveQueue.prepare(expected, listOf(MovePoint(80f, 120f)), false))
        assertEquals(NativeMoveQueue.CAPACITY, unchanged.size)
        assertEquals(expected.last().targetX, unchanged.last().targetX)
    }

    @Test
    fun staleQueueAndIncorrectFinalGoalNeverRegisterAReplacement() = nativeFixture { engine, ui, unit ->
        unit.queueMoveWaypoint(80f, 120f)
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        val prior = ui.getPlayerCommand()
        assertNull(submit(engine, unit, expected, listOf(MovePoint(81f, 120f)), false))
        unit.queueMoveWaypoint(400f, 500f)
        assertNull(submit(engine, unit, expected, listOf(MovePoint(80f, 120f)), false))
        assertEquals(listOf(prior), engine.commandController.networkPendingCommands.toArray().toList())
        assertEquals(2, unit.waypointCount)
    }

    @Test
    fun registrationFailureRollsBackOnlyTheBatchAndKeepsIssuedCommands() = nativeFixture { engine, ui, unit ->
        unit.queueMoveWaypoint(80f, 120f)
        val expected = assertNotNull(NativeMoveQueue.snapshot(unit))
        val priorNetwork = ui.getPlayerCommand()
        val priorLocal = CommandControllerCommand(null)
        val priorIssued = CommandControllerCommand(null)
        engine.commandController.localPendingCommands.add(priorLocal)
        engine.commandController.issuedCommands.add(priorIssued)
        ui.failAt = ui.created + 2 // Fail after native getPlayerCommand has already registered the second entry.
        assertNull(submit(engine, unit, expected,
            listOf(MovePoint(40f, 60f), MovePoint(80f, 120f)), false))
        assertEquals(listOf(priorNetwork), engine.commandController.networkPendingCommands.toArray().toList())
        assertEquals(listOf(priorLocal), engine.commandController.localPendingCommands.toArray().toList())
        assertEquals(listOf(priorIssued), engine.commandController.issuedCommands.toArray().toList())
        assertTrue(NativeMoveQueue.sameQueue(unit, expected))
    }

    @Test
    fun scriptStateAndUnsupportedOrdersAreNeverSilentlyLostByNativeCopyFrom() = nativeFixture { _, _, unit ->
        val head = unit.queueMoveWaypoint(80f, 120f)
        val changes: List<(Waypoint) -> Unit> = listOf(
            { it.automatic = true },
            { it.addedByAction = true },
            { it.maxWaypointDuration = 0f },
            { it.repairReclaimRangePadding = 0f },
            { it.setAttackMoveTo(80f, 120f) },
            { Waypoint::class.java.getDeclaredField("unitId").apply { isAccessible = true }.setLong(it, 321L) },
        )
        for (change in changes) {
            head.setMoveTo(80f, 120f)
            change(head)
            assertNull(NativeMoveQueue.snapshot(unit))
        }
        head.setMoveTo(80f, 120f)
        head.formationFaceEnemy = true
        assertNotNull(NativeMoveQueue.snapshot(unit), "The formation flag is preserved by native copying")
    }

    private fun roundTrip(command: CommandControllerCommand): CommandControllerCommand {
        val output = OutputNetStream().also(command::write)
        return CommandControllerCommand(null).apply { read(InputNetStream(output.toByteArray())) }
    }

    private fun waypointBytes(waypoint: Waypoint): ByteArray = OutputNetStream().also(waypoint::write).toByteArray()

    private fun nativeFixture(block: (GameEngine, QueueInterface, OrderableUnit) -> Unit) {
        val engineField = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val precedingEngine = engineField.get(null)
        var teams: Array<Team?>? = null
        var precedingTeam: Team? = null
        try {
            val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
            engineField.set(null, engine)
            @Suppress("UNCHECKED_CAST")
            val nativeTeams = Team::class.java.getDeclaredField("teamArray").apply { isAccessible = true }.get(null) as Array<Team?>
            teams = nativeTeams
            precedingTeam = nativeTeams[0]
            GameEngine::class.java.getDeclaredField("gameUpdateLock").apply { isAccessible = true }.set(engine, Any())
            val team = (unsafe.allocateInstance(Player::class.java) as Player).apply { teamId = 0 }
            nativeTeams[0] = team
            engine.playerTeam = team
            engine.networkEngine = (unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine).apply { networked = true }
            engine.replayEngine = unsafe.allocateInstance(ReplayEngine::class.java) as ReplayEngine
            engine.commandController = CommandController()
            engine.gameTimeMillis = 1234
            val ui = QueueInterface(unsafe.allocateInstance(InterfaceEngine::class.java) as InterfaceEngine)
            ui.failAt = Int.MAX_VALUE
            engine.interfaceEngine = ui.native
            commandFactory = ui::createCommand
            val unit = (unsafe.allocateInstance(QueueTank::class.java) as QueueTank).apply {
                this.team = team; id = 73L; x = 13f; y = 50f
                OrderableUnit::class.java.getDeclaredField("waypoints").apply { isAccessible = true }
                    .set(this, arrayOfNulls<Waypoint>(30))
            }
            block(engine, ui, unit)
        } finally {
            teams?.set(0, precedingTeam)
            engineField.set(null, precedingEngine)
        }
    }

    private class QueueTank : Tank(true) {
        override fun getBuildRange(type: UnitTypeInterface): Float = 80f
        override fun getUnitTypeInterface(): UnitTypeInterface = UnitType.tank
    }

    private class QueueInterface(val native: InterfaceEngine) {
        var created = 0
        var failAt = Int.MAX_VALUE
        fun getPlayerCommand(): CommandControllerCommand = native.getPlayerCommand()
        fun createCommand(): CommandControllerCommand {
            val command = native.getPlayerCommand()
            created++
            check(created != failAt) { "Injected failure after native registration" }
            return command
        }
    }

    private lateinit var commandFactory: () -> CommandControllerCommand

    private fun submit(
        engine: GameEngine,
        unit: OrderableUnit,
        expected: List<Waypoint>,
        targets: List<MovePoint>,
        retainHead: Boolean,
    ) = NativeMoveQueue.submitUsing(engine, unit, expected, targets, retainHead, commandFactory)

    private companion object {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
    }
}
