package io.github.rwpp.game.movement

import android.graphics.Rect
import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.units.MovementType
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.Unit as NativeUnit
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.af as NativePathNode
import com.corrodinggames.rts.game.units.land.Builder
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.path.FlowFieldPath
import com.corrodinggames.rts.gameFramework.path.MapCosts
import com.corrodinggames.rts.gameFramework.path.Path
import com.corrodinggames.rts.gameFramework.path.PathEngine
import com.corrodinggames.rts.gameFramework.path.PathSolverNode
import io.github.rwpp.mixin.movement.SingleUnitMoveNativePathMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.LinkedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMoveTerrainTest {
    @Test
    fun capturesNativeColumnMajorTerrainWithoutReadingGlobalUnitCostsAndKeepsItsOwnCopy() = withWorld(40, 25) { world ->
        val goal = MovePoint(300f, 200f)
        val terrainIndex = 7 * world.map.mapHeightInTiles + 6
        world.costs.terrainCost[terrainIndex] = -1
        world.costs.unitBlocked[5 * world.map.mapHeightInTiles + 5] = -1
        world.costs.unitInfluence[5 * world.map.mapHeightInTiles + 5] = -1
        world.costs.clearanceCost.fill(0)
        val source = world.costs.terrainCost.copyOf()
        val snapshot = assertNotNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
        assertFalse(snapshot.isClear(MovePoint(150f, 130f), MovePoint(150f, 130f), 0f))
        assertTrue(snapshot.isClear(MovePoint(110f, 110f), MovePoint(110f, 110f), 0f))
        assertTrue(source.contentEquals(world.costs.terrainCost), "capture must not rebuild or write native terrain")
        world.costs.terrainCost[terrainIndex] = 0
        assertFalse(snapshot.isClear(MovePoint(150f, 130f), MovePoint(150f, 130f), 0f))
    }

    @Test
    fun groundMovementSnapshotsFollowTheCorrespondingNativeTerrainCosts() = withWorld(40, 25) { world ->
        val tiles = arrayOf(
            null,
            allocate(MapTile::class.java).apply { water = true },
            allocate(MapTile::class.java).apply { cliff = true },
            allocate(MapTile::class.java).apply { trees = true },
            allocate(MapTile::class.java).apply { lava = true; cliff = true },
            allocate(MapTile::class.java).apply { blockLevel = -1 },
        )
        world.map.uniqueTiles = tiles
        val height = world.map.mapHeightInTiles
        val positions = (1 until tiles.size).map { index -> MovePoint((index + 4) * 20f + 10f, 130f) }
        world.map.groundLayer.tileIds = ShortArray(world.map.mapWidthInTiles * height).apply {
            for (index in 1 until tiles.size) this[(index + 4) * height + 6] = index.toShort()
        }
        val rebuild = MapCosts::class.java.getDeclaredMethod("rebuildTerrainCost").apply { isAccessible = true }
        val expected = listOf(
            MovementType.LAND to listOf(false, false, false, false, false),
            MovementType.HOVER to listOf(true, true, false, false, false),
            MovementType.OVER_CLIFF to listOf(false, true, true, false, false),
            MovementType.OVER_CLIFF_WATER to listOf(true, true, true, false, false),
        )
        val goal = MovePoint(300f, 200f)
        for ((type, clear) in expected) {
            world.mover.fixtureMovementType = type
            rebuild.invoke(world.engine.pathEngine.getCosts(type))
            val snapshot = assertNotNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
            assertEquals(clear, positions.map { snapshot.isClear(it, it, 0f) }, type.name)
        }
        world.mover.fixtureMovementType = MovementType.AIR
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
        assertNull(NativeMoveTerrain.motion(world.mover))
    }

    @Test
    fun knownBuildingRasterUsesNativeOriginAndInclusiveFootprintButDoesNotRevealHiddenBuildings() = withWorld(50, 30) { world ->
        val goal = MovePoint(500f, 300f)
        val visible = world.building(211f, 151f, world.enemy).apply {
            fixtureOriginX = 11f
            fixtureOriginY = 11f
            fixtureFootprint = Rect(-1, 0, 1, 1)
        }
        val hidden = world.building(310f, 150f, world.enemy)
        world.viewer.fogGrid[15][7] = 10
        val ownInFog = world.building(410f, 150f, world.viewer)
        world.viewer.fogGrid[20][7] = 10
        val invisibleCustomLike = world.building(510f, 150f, world.enemy).apply { fixturePublicVisibility = false }

        val snapshot = assertNotNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
        fun blockedAt(x: Float, y: Float) = !snapshot.isClear(MovePoint(x, y), MovePoint(x, y), 0f)
        assertTrue(blockedAt(190f, 150f), "left footprint tile must be included")
        assertTrue(blockedAt(230f, 170f), "right/bottom footprint tiles are inclusive")
        assertFalse(blockedAt(250f, 150f))
        assertFalse(blockedAt(hidden.x, hidden.y), "unseen enemy positions must not enter the snapshot")
        assertTrue(blockedAt(ownInFog.x, ownInFog.y))
        assertFalse(blockedAt(invisibleCustomLike.x, invisibleCustomLike.y))
        visible.dead = true
        assertFalse(snapshot.isClear(MovePoint(190f, 150f), MovePoint(190f, 150f), 0f), "snapshot must stay detached")
        val next = assertNotNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
        assertTrue(next.isClear(MovePoint(190f, 150f), MovePoint(190f, 150f), 0f))
    }

    @Test
    fun rejectsOutOfMapAndUnreadyFogInsteadOfClampingOrExposingUnitPositions() = withWorld(40, 25) { world ->
        val goal = MovePoint(300f, 200f)
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, MovePoint(800f, 200f), listOf(goal)))
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, MovePoint(-0.1f, 200f), listOf(goal)))
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(MovePoint(Float.NaN, 200f))))
        world.map.fogOfWarEnabled = true
        world.viewer.fogGrid = null
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
    }

    @Test
    fun appliesCaptureBudgetToTheEntireNativeDetourNotOnlyTheStartGoalLine() = withWorld(300, 300) { world ->
        val goal = MovePoint(200f, 80f)
        assertNotNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(goal)))
        assertNull(NativeMoveTerrain.capture(world.engine, world.mover, goal, listOf(MovePoint(5900f, 5900f), goal)))
    }

    @Test
    fun readsNativeThrottleAndDecelerationAndRejectsUnmodelledMotionModes() = withWorld(40, 25) { world ->
        val unit = world.mover
        unit.moveThrottle = -0.35f
        unit.currentTurnRate = -1.25f
        unit.angle = 82f
        val motion = assertNotNull(NativeMoveTerrain.motion(unit))
        assertEquals(-0.35f, motion.speedFraction)
        assertEquals(-1.25f, motion.currentTurnRate)
        assertEquals(-1f, motion.turnAcceleration)
        assertEquals(0.08f, motion.acceleration)
        assertEquals(0.04f, motion.deceleration, "old getBuildSpeed name actually binds the native braking coefficient")
        unit.inFormation = true
        unit.formationSlotCount = 1
        assertTrue(assertNotNull(NativeMoveTerrain.motion(unit)).formationLeader)
        unit.formationSlotCount = 2
        assertFalse(assertNotNull(NativeMoveTerrain.motion(unit)).formationLeader)
        unit.fixtureYAxis = 0.8f
        assertNull(NativeMoveTerrain.motion(unit))
        unit.fixtureYAxis = 1f
        unit.fixtureIgnoreBody = true
        assertNull(NativeMoveTerrain.motion(unit))
        unit.fixtureIgnoreBody = false
        unit.fixtureSliding = true
        assertNull(NativeMoveTerrain.motion(unit))
        unit.fixtureSliding = false
        unit.transportedBy = world.building(200f, 200f, world.viewer)
        assertNull(NativeMoveTerrain.motion(unit))
    }

    @Test
    fun usesNativeHeadingAndVelocityOnlyForBodyIndependentSliding() = withWorld(40, 25) { world ->
        val unit = world.mover
        unit.fixtureMovementType = MovementType.OVER_CLIFF
        unit.angle = 82f
        unit.moveHeading = -30f
        unit.speedX = 0.8f
        unit.speedY = -0.2f
        val bodySteered = assertNotNull(NativeMoveTerrain.motion(unit))
        assertEquals(MovementType.OVER_CLIFF, bodySteered.movementType)
        assertEquals(82f, bodySteered.headingDegrees)
        assertNull(bodySteered.slidingVelocity)
        unit.fixtureMovementType = MovementType.HOVER
        unit.fixtureIgnoreBody = true
        unit.fixtureSliding = true
        val sliding = assertNotNull(NativeMoveTerrain.motion(unit))
        assertEquals(MovementType.HOVER, sliding.movementType)
        assertEquals(-30f, sliding.headingDegrees)
        assertEquals(MovePoint(0.8f, -0.2f), sliding.slidingVelocity)
    }

    @Test
    fun readsOnlyAnAppliedPathForTheExactCurrentMoveAndRejectsIncompletePaths() = withWorld(40, 25) { world ->
        val goal = MovePoint(300f, 200f)
        val unit = world.mover
        unit.installPath(goal, listOf(MovePoint(120f, 100f), goal))
        val snapshot = assertNotNull(NativeMoveTerrain.nativeRoute(unit, goal))
        assertEquals(listOf(MovePoint(120f, 100f), goal), snapshot)
        unit.rewriteFirstNode(180f, 110f)
        assertEquals(MovePoint(120f, 100f), snapshot.first(), "native node mutation must not change a captured route")
        assertNull(NativeMoveTerrain.nativeRoute(unit, MovePoint(310f, 200f)))
        field(OrderableUnit::class.java, "pathTruncated").setBoolean(unit, true)
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal))
        field(OrderableUnit::class.java, "pathTruncated").setBoolean(unit, false)
        field(OrderableUnit::class.java, "pendingPath").set(unit, allocate(Path::class.java))
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal))
        field(OrderableUnit::class.java, "pendingPath").set(unit, null)
        unit.installPath(goal, listOf(MovePoint(120f, 100f), MovePoint(260f, 160f)))
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal), "nearest-reachable fallback must not be extended through an obstacle")
    }

    @Test
    fun straightCommandPredictionMatchesNativeNodeBuffer() = withWorld(160, 50) { world ->
        val from = MovePoint(world.mover.x, world.mover.y)
        val truncatedGoal = MovePoint(from.x + 2420f, from.y)
        val goals = listOf(0f, 39.9f, 40f, 99.9f, 100f, 2419f).map { MovePoint(from.x + it, from.y) } +
            listOf(truncatedGoal, MovePoint(557.4f, 318.75f))
        for (goal in goals) {
            val native = world.mover.requestRealStraightPath(goal)
            val predicted = directMoveNodes(from, goal)
            assertEquals(goal == truncatedGoal, world.mover.rwppMovePathTruncated(), "native truncation to $goal")
            if (goal == truncatedGoal) {
                assertEquals(119, native.size)
                assertNull(predicted)
                assertTrue(native.last().distanceTo(goal) > 10f, "truncated native path must omit its final target")
            } else {
                val nodes = assertNotNull(predicted)
                assertEquals(native.size, nodes.size, "node count to $goal")
                assertEquals(goal, native.last())
                assertEquals(goal, nodes.last())
                for ((actual, model) in native.zip(nodes)) {
                    assertTrue(actual.distanceTo(model) < 1.5f, "native $actual differs from predicted $model")
                }
            }
        }
    }

    @Test
    fun nativeRouteCanReadTheHeadOfAShiftQueueWithoutChangingItsSuffix() = withWorld(40, 25) { world ->
        val unit = world.mover
        val goal = MovePoint(300f, 200f)
        val points = listOf(MovePoint(120f, 100f), goal)
        unit.installPath(goal, points)
        val originalHead = unit.currentWaypoint
        val suffix = Waypoint().apply { setMoveTo(600f, 300f) }
        field(OrderableUnit::class.java, "waypoints").set(unit, arrayOf(originalHead, suffix))
        field(OrderableUnit::class.java, "waypointsCount").setInt(unit, 2)
        assertEquals(points, NativeMoveTerrain.nativeRoute(unit, goal))
        assertTrue(unit.getWaypoint(1) === suffix)
        assertEquals(2, unit.waypointCount)
    }

    @Test
    fun buildApproachEndsSafelyInsideNativeRangeAndNeverAppendsTheBuildingCenter() = withWorld(40, 25) { world ->
        val unit = world.mover
        val goal = MovePoint(300f, 200f)
        val approach = MovePoint(250f, 200f)
        unit.installPath(goal, listOf(MovePoint(120f, 100f), approach, goal))
        unit.currentWaypoint.setBuildTo(goal.x, goal.y, UnitType.turret, 2)
        assertEquals(listOf(MovePoint(120f, 100f), approach), NativeMoveTerrain.nativeRoute(unit, goal))
        assertEquals(2, unit.currentWaypoint.buildTechLevel)
        // A MOVE may complete 7px short; an endpoint barely inside build range is insufficient.
        unit.installPath(goal, listOf(MovePoint(120f, 100f), MovePoint(225f, 200f)))
        unit.currentWaypoint.setBuildTo(goal.x, goal.y, UnitType.turret, 2)
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal))
    }

    @Test
    fun truncatedBuildRouteAcceptsAReachableEndpointWithinRangeWithoutUsingBlockedGoalTiles() = withWorld(160, 20) { world ->
        val unit = world.mover
        val goal = MovePoint(2861f, 83f)
        val tiles = (4..140).map { x -> PathSolverNode(x.toShort(), 4) }
        unit.installPendingPath(goal, tiles)
        unit.currentWaypoint.setBuildTo(goal.x, goal.y, UnitType.turret, 2)
        world.useReleasedNetworkPaths()
        val continuation = assertNotNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))
        unit.applyRealPendingPath(world.engine)
        val recovered = assertNotNull(NativeMoveTerrain.nativeRoute(unit, goal, continuation))
        assertEquals(MovePoint(2810f, 90f), recovered.last())
        assertTrue(goal !in recovered)
        unit.installPendingPath(goal, tiles.dropLast(5))
        unit.currentWaypoint.setBuildTo(goal.x, goal.y, UnitType.turret, 2)
        assertNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal), "The solver must still reach build range")
    }

    @Test
    fun releasedNativeSolverTailCompletesTheRealCappedPathAfterItsHeadAdvances() = withWorld(160, 20) { world ->
        val unit = world.mover
        val goal = MovePoint(2861f, 83f)
        val tiles = (4..143).map { x -> PathSolverNode(x.toShort(), 4) }
        val pending = unit.installPendingPath(goal, tiles)
        world.useReleasedNetworkPaths()

        val continuation = assertNotNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))
        assertEquals(0, unit.rwppMovePathCount(), "Taking the snapshot must not populate the live path")
        assertTrue(unit.rwppMovePendingPath() === pending)
        assertEquals(140, pending.getNodes().size)

        unit.applyRealPendingPath(world.engine)
        assertTrue(unit.rwppMovePathTruncated())
        assertEquals(120, unit.rwppMovePathCount())
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal))
        repeat(8) { unit.advancePathNode() }
        assertEquals(112, unit.rwppMovePathCount())
        tiles.last().x = 0 // The detached solver data must not be read again.
        val recovered = assertNotNull(NativeMoveTerrain.nativeRoute(unit, goal, continuation))
        assertEquals((12..142).map { MovePoint(it * 20f + 10f, 90f) } + goal, recovered)

        unit.fixtureMovementType = MovementType.OVER_CLIFF
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal, continuation),
            "A changed movement type must not reuse a captured solver suffix")
        unit.fixtureMovementType = MovementType.LAND
        unit.setPathNode(unit.rwppMovePathCount() - 1, 2500f, 90f)
        assertNull(NativeMoveTerrain.nativeRoute(unit, goal, continuation),
            "A changed live tail must not receive the captured solver suffix")
    }

    @Test
    fun exactNativeCapacityStillRequiresRestoringTheRequestedPointInsideTheGoalTile() = withWorld(140, 20) { world ->
        val unit = world.mover
        val goal = MovePoint(2461f, 83f)
        val tiles = (4..123).map { x -> PathSolverNode(x.toShort(), 4) }
        unit.installPendingPath(goal, tiles)
        world.useReleasedNetworkPaths()

        val continuation = assertNotNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))
        unit.applyRealPendingPath(world.engine)
        assertEquals(120, unit.rwppMovePathCount())
        assertTrue(unit.rwppMovePathTruncated(), "Vanilla sets the flag upon adding its 120th node")
        val recovered = assertNotNull(NativeMoveTerrain.nativeRoute(unit, goal, continuation))
        assertEquals((4..122).map { MovePoint(it * 20f + 10f, 90f) }, recovered.take(119))
        assertTrue(recovered.size in 120..121)
        assertEquals(goal, recovered.last())
        assertEquals(MovePoint(2470f, 90f), unit.rwppMovePathNodes()!![119]!!.let { MovePoint(it.a, it.b) })
    }

    @Test
    fun pendingContinuationRejectsUnreleasedSolverDataWrongEndTilesAndFlowFields() = withWorld(160, 20) { world ->
        val unit = world.mover
        val goal = MovePoint(2861f, 83f)
        val tiles = (4..143).map { x -> PathSolverNode(x.toShort(), 4) }
        world.useReleasedNetworkPaths()
        val unreleased = unit.installPendingPath(goal, tiles).apply { releasedForUse = false }
        assertNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))
        unit.applyRealPendingPath(world.engine)
        assertTrue(unit.rwppMovePendingPath() === unreleased)
        assertEquals(0, unit.rwppMovePathCount())
        unreleased.releasedForUse = true
        assertNotNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))

        unit.installPendingPath(goal, tiles.dropLast(1))
        assertNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal),
            "A nearest-reachable endpoint must not be replaced with the player's target")
        unit.installPendingPath(goal, tiles, allocate(FlowFieldPath::class.java))
        assertNull(NativeMoveTerrain.pendingContinuation(world.engine, unit, goal))
    }

    @Test
    fun nativePathAccessorsBindActualFieldsAndNeverGenerateWrites() {
        AsmRegistry.clear()
        try {
            val name = "com/corrodinggames/rts/game/units/OrderableUnit"
            val source = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
            AsmRegistry.register(NativeMovePathAccessMixin::class.java)
            AsmRegistry.register(SingleUnitMoveNativePathMixin::class.java)
            val transformed = AsmProcessor().transform(name, source, javaClass.classLoader)
            val node = ClassNode().also { ClassReader(transformed).accept(it, 0) }
            assertTrue("io/github/rwpp/game/movement/NativeMovePathAccess" in node.interfaces)
            val expected = mapOf(
                "rwppMovePathGoalX" to "pathGoalX",
                "rwppMovePathGoalY" to "pathGoalY",
                "rwppMovePathTruncated" to "pathTruncated",
                "rwppMovePathCount" to "activePathCount",
                "rwppMovePathNodes" to "pathNodes",
                "rwppMovePendingPath" to "pendingPath",
            )
            for ((methodName, fieldName) in expected) {
                val instructions = node.methods.single { it.name == methodName }.instructions.toArray()
                val reads = instructions.filterIsInstance<FieldInsnNode>()
                assertEquals(1, reads.size)
                assertEquals(fieldName, reads.single().name)
                assertEquals(Opcodes.GETFIELD, reads.single().opcode)
            }
            val apply = node.methods.single {
                it.name == "applyPendingPath" && it.desc == "(Lcom/corrodinggames/rts/gameFramework/GameEngine;)V"
            }.instructions.toArray()
            val hook = apply.indexOfFirst { it is MethodInsnNode && it.name == "beforeApplyPath" &&
                    it.owner.endsWith("/SingleUnitMoveNativePathMixin") }
            val pending = apply.indexOfFirst { it is FieldInsnNode && it.opcode == Opcodes.GETFIELD && it.name == "pendingPath" }
            assertTrue(hook >= 0 && pending > hook, "Capture must precede the native read that consumes pendingPath")
        } finally {
            AsmRegistry.clear()
        }
    }

    private class World(val engine: RWGameEngine, val map: NativeMap, val viewer: Player, val enemy: Player, val costs: MapCosts) {
        val mover = allocate(FixtureUnit::class.java).apply {
            initialize(80f, 80f, viewer, false)
            NativeUnit.unitList.add(this)
        }

        fun building(x: Float, y: Float, owner: Team): FixtureUnit = allocate(FixtureUnit::class.java).apply {
            initialize(x, y, owner, true)
            NativeUnit.unitList.add(this)
        }

        fun useReleasedNetworkPaths() {
            engine.networkEngine = allocate(NetworkEngine::class.java).apply { networked = true }
        }
    }

    /** A native concrete unit, bypassing only constructors that load images/audio. */
    private class FixtureUnit : Builder(false), NativeMovePathAccess {
        override fun getUnitTypeInterface(): com.corrodinggames.rts.game.units.UnitTypeInterface =
            com.corrodinggames.rts.game.units.UnitType.builder
        var fixtureBuilding = false
        var fixtureFootprint = Rect(0, 0, 0, 0)
        var fixtureOriginX = 0f
        var fixtureOriginY = 0f
        var fixturePublicVisibility = true
        var fixtureYAxis = 1f
        var fixtureIgnoreBody = false
        var fixtureSliding = false
        lateinit var fixtureMovementType: MovementType

        fun initialize(atX: Float, atY: Float, owner: Team, building: Boolean) {
            x = atX
            y = atY
            team = owner
            radius = 8f
            collisionEnabled = true
            buildProgress = 1f
            fixtureBuilding = building
            fixtureFootprint = Rect(0, 0, 0, 0)
            fixturePublicVisibility = true
            fixtureYAxis = 1f
            fixtureMovementType = MovementType.LAND
        }

        override fun isBuilding() = fixtureBuilding
        override fun getFootprint() = fixtureFootprint
        override fun getMinimapOriginX() = fixtureOriginX
        override fun getMinimapOriginY() = fixtureOriginY
        override fun isVisibleToAllPlayers() = fixturePublicVisibility
        override fun getMovementType() = fixtureMovementType
        override fun isAirUnit() = false
        override fun canReceiveOrders() = true
        override fun getMoveYAxisScaling() = fixtureYAxis
        override fun getMoveYAxisScaleReciprocal() = 1f / fixtureYAxis
        override fun isMoveIgnoringBody() = fixtureIgnoreBody
        override fun isMoveSlidingMode() = fixtureSliding
        override fun getMoveSpeed() = 2f
        override fun getTurnSpeed() = 3f
        override fun getTurnAcceleration() = -1f
        override fun getMoveAccelerationSpeed() = 0.08f
        override fun getBuildSpeed() = 0.04f
        override fun getReverseSpeedPercentage() = 0.6f
        override fun getBuildRange(type: UnitTypeInterface) = 80f

        fun installPath(goal: MovePoint, points: List<MovePoint>) {
            val waypoint = Waypoint().apply { setMoveTo(goal.x, goal.y) }
            field(OrderableUnit::class.java, "waypoints").set(this, arrayOf(waypoint))
            field(OrderableUnit::class.java, "waypointsCount").setInt(this, 1)
            field(OrderableUnit::class.java, "pathGoalX").setFloat(this, goal.x)
            field(OrderableUnit::class.java, "pathGoalY").setFloat(this, goal.y)
            pathNodes = points.map { point -> NativePathNode().apply { a = point.x; b = point.y } }.toTypedArray()
            activePathCount = points.size
        }

        fun installPendingPath(goal: MovePoint, tiles: List<PathSolverNode>, path: Path = allocate(Path::class.java)): Path {
            installPath(goal, emptyList())
            pathNodes = arrayOfNulls<NativePathNode>(120)
            field(Path::class.java, "nodes").set(path, LinkedList(tiles))
            field(Path::class.java, "movementType").set(path, MovementType.LAND)
            field(Path::class.java, "startX").setShort(path, (x / 20f).toInt().toShort())
            field(Path::class.java, "startY").setShort(path, (y / 20f).toInt().toShort())
            field(Path::class.java, "endX").setShort(path, (goal.x / 20f).toInt().toShort())
            field(Path::class.java, "endY").setShort(path, (goal.y / 20f).toInt().toShort())
            path.releasedForUse = true
            field(OrderableUnit::class.java, "pendingPath").set(this, path)
            return path
        }

        fun applyRealPendingPath(engine: GameEngine) {
            OrderableUnit::class.java.getDeclaredMethod("applyPendingPath", GameEngine::class.java)
                .apply { isAccessible = true }.invoke(this, engine)
        }

        fun requestRealStraightPath(goal: MovePoint): List<MovePoint> {
            pathNodes = arrayOfNulls<NativePathNode>(120)
            requestPathTo(goal.x, goal.y, 0, false, false)
            return List(activePathCount) { index -> pathNodes[index].let { MovePoint(it.a, it.b) } }
        }

        fun rewriteFirstNode(x: Float, y: Float) {
            pathNodes[0].a = x
            pathNodes[0].b = y
        }

        override fun rwppMovePathGoalX() = field(OrderableUnit::class.java, "pathGoalX").getFloat(this)
        override fun rwppMovePathGoalY() = field(OrderableUnit::class.java, "pathGoalY").getFloat(this)
        override fun rwppMovePathTruncated() = field(OrderableUnit::class.java, "pathTruncated").getBoolean(this)
        override fun rwppMovePathCount() = activePathCount
        override fun rwppMovePathNodes(): Array<NativePathNode?>? = Array(pathNodes.size) { pathNodes[it] }
        override fun rwppMovePendingPath() = field(OrderableUnit::class.java, "pendingPath").get(this) as Path?
    }

    private companion object {
        val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as sun.misc.Unsafe

        fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

        fun <T> withWorld(width: Int, height: Int, block: (World) -> T): T {
            val engineField = field(GameEngine::class.java, "al")
            val previousEngine = engineField.get(null)
            val nativeUnits = NativeUnit.unitList
            val previousUnits = nativeUnits.items().take(nativeUnits.size)
            try {
                nativeUnits.clear()
                val engine = allocate(RWGameEngine::class.java)
                engineField.set(null, engine)
                field(GameEngine::class.java, "gameUpdateLock").set(engine, Any())
                engine.gameLoopActive = true
                val viewer = allocate(Player::class.java)
                val enemy = allocate(Player::class.java)
                val map = allocate(NativeMap::class.java).apply {
                    tileWidth = 20
                    tileHeight = 20
                    halfTileWidth = 10
                    halfTileHeight = 10
                    inverseTileWidth = 0.05f
                    inverseTileHeight = 0.05f
                    mapWidthInTiles = width
                    mapHeightInTiles = height
                    mapLoaded = true
                    fogOfWarEnabled = true
                    groundLayer = allocate(MapLayer::class.java).also {
                        it.width = width
                        it.height = height
                    }
                }
                viewer.fogGrid = Array(width) { ByteArray(height) }
                engine.map = map
                engine.playerTeam = viewer
                val movementTypes = listOf(MovementType.LAND, MovementType.HOVER,
                    MovementType.OVER_CLIFF, MovementType.OVER_CLIFF_WATER)
                val costsByMovement = movementTypes.associateWith { type ->
                    allocate(MapCosts::class.java).also { costs ->
                        field(MapCosts::class.java, "width").setInt(costs, width)
                        field(MapCosts::class.java, "height").setInt(costs, height)
                        field(MapCosts::class.java, "movementType").set(costs, type)
                        costs.terrainCost = ByteArray(width * height)
                        costs.unitBlocked = ByteArray(width * height)
                        costs.unitInfluence = ByteArray(width * height)
                        costs.clearanceCost = ByteArray(width * height)
                    }
                }
                val landCosts = costsByMovement.getValue(MovementType.LAND)
                engine.pathEngine = allocate(PathEngine::class.java).also {
                    field(PathEngine::class.java, "map").set(it, map)
                    field(PathEngine::class.java, "mapWidth").setShort(it, width.toShort())
                    field(PathEngine::class.java, "mapHeight").setShort(it, height.toShort())
                    field(PathEngine::class.java, "costsArray").set(it, costsByMovement.values.toTypedArray())
                    for (costs in costsByMovement.values) field(MapCosts::class.java, "engine").set(costs, it)
                    it.costsLand = landCosts
                }
                return block(World(engine, map, viewer, enemy, landCosts))
            } finally {
                nativeUnits.clear()
                previousUnits.forEach(nativeUnits::add)
                engineField.set(null, previousEngine)
            }
        }
    }
}
