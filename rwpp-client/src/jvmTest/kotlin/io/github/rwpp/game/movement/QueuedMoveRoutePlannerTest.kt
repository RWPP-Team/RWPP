package io.github.rwpp.game.movement

import android.graphics.RectF
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.Player
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.buildings.turrets.Turret
import com.corrodinggames.rts.game.units.land.Builder
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ReplayEngine
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.path.PathEngine
import sun.misc.Unsafe
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.test.*

class QueuedMoveRoutePlannerTest {
    private fun clearGrid() = MoveNavigationGrid(0f, 0f, 120, 120, 20f, 20f, BooleanArray(120 * 120))
    private fun builderMotion() = MoveMotion(MovePoint(210f, 210f), 0f, 0f, 0f,
        0.8f, 3.8f, 0.35f, 0.04f, 0.1f, 0.6f, 10f)

    @Test
    fun completeQueuedMotionAgreesWithTheUnmodifiedNativeBuilder() = withNativeEngine { engine ->
        for (targets in listOf(
            listOf(MovePoint(350f, 210f), MovePoint(500f, 210f), MovePoint(650f, 210f)),
            listOf(MovePoint(350f, 210f), MovePoint(450f, 140f), MovePoint(550f, 140f)),
            listOf(MovePoint(350f, 210f), MovePoint(300f, 250f), MovePoint(450f, 250f)),
        )) {
            val unit = Builder(true).apply {
                x = 210f; y = 210f; angle = 0f; buildProgress = 1f
                field(javaClass, "needsInitialFogUpdate").setBoolean(this, false)
            }
            targets.forEach { unit.queueMoveWaypoint(it.x, it.y) }
            var frames = 0
            while (unit.waypointCount > 0 && frames < 6000) {
                engine.simulationTick = frames
                engine.gameTimeMillis = frames * 1000 / 60
                unit.update(1f)
                engine.pathEngine.update(1f)
                frames++
            }
            assertEquals(0, unit.waypointCount, "The native queue must complete")
            val prediction = MoveRoutePlanner.estimateQueued(clearGrid(), builderMotion(), targets)
            assertTrue(prediction.completed, prediction.toString())
            assertTrue(abs(prediction.time - frames) <= 4f,
                "Native full-update=$frames frames, queued prediction=$prediction targets=$targets")
        }
    }

    @Test
    fun shorelineWaypointsPutAnUnmodifiedBuilderInRangeOfTheOriginalBuild() = withNativeEngine { engine ->
        val grid = threeTileLavaStrip(engine)
        val start = MovePoint(470f, 1580f)
        val unit = Builder(true).apply {
            x = start.x; y = start.y; angle = 0f; buildProgress = 1f
            field(javaClass, "needsInitialFogUpdate").setBoolean(this, false)
        }
        val bank = MovePoint(510f, 1570f)
        val center = MovePoint(600f, 1580f)
        val range = unit.getBuildRange(UnitType.turret)
        val motion = builderMotion().copy(position = start)
        fun plan(destination: MovePoint) = MoveShoreBuild.plan(grid, motion, bank,
            MoveBuildApproach(destination, range)) { it.x in 520f..<580f }
        val targets = assertNotNull(plan(center))
        assertNull(plan(MovePoint(620f, 1580f)))
        targets.forEach { unit.queueMoveWaypoint(it.x, it.y) }
        var frames = 0
        while (unit.waypointCount > 0 && frames < 600) {
            engine.simulationTick = frames
            engine.gameTimeMillis = frames * 1000 / 60
            unit.update(1f)
            engine.pathEngine.update(1f)
            frames++
        }
        assertEquals(0, unit.waypointCount)
        val handoff = MovePoint(unit.x, unit.y)
        assertTrue(handoff.x < 520f && handoff.distanceTo(center) <= range, "Native handoff=$handoff targets=$targets")
    }

    @Test
    fun nativeBuilderResumesWorkOnTheSameTurretAcrossLava() = withNativeEngine { engine ->
        val grid = threeTileLavaStrip(engine)
        val start = MovePoint(470f, 1580f)
        val initialProgress = 0.4f
        field(GameEngine::class.java, "viewRectF").set(engine, RectF())
        val ownTeam = Player(0, false)
        val unit = Builder(true).apply {
            x = start.x; y = start.y; angle = 0f; buildProgress = 1f; team = ownTeam
            field(javaClass, "needsInitialFogUpdate").setBoolean(this, false)
        }
        val center = MovePoint(600f, 1580f)
        val site = Turret(true).apply {
            x = center.x; y = center.y; buildProgress = initialProgress; team = ownTeam
        }
        val repair = Waypoint().apply { setRepair(site) }
        val range = assertNotNull(repair.constructionRange(unit))
        val bank = MovePoint(510f, 1570f)
        val targets = assertNotNull(MoveShoreBuild.plan(grid, builderMotion().copy(position = start), bank,
            MoveBuildApproach(center, range)) { it.x in 520f..<580f })
        val orders = assertNotNull(NativeMoveQueue.prepare(listOf(repair), targets, retainHead = true, buildRange = range))
        orders.forEach { unit.allocateWaypoint().copyFrom(it) }
        var frames = 0
        while (site.buildProgress == initialProgress && frames < 600) {
            engine.simulationTick = frames
            engine.gameTimeMillis = frames * 1000 / 60
            unit.update(1f)
            engine.pathEngine.update(1f)
            frames++
        }
        assertTrue(unit.x < 520f && site.buildProgress > initialProgress, "Native repair did not resume on the original turret")
        assertSame(site, unit.currentWaypoint.targetUnit)
    }

    private fun threeTileLavaStrip(engine: GameEngine): MoveNavigationGrid {
        val blocked = BooleanArray(120 * 120) { it / 120 in 26..28 }
        val costs = engine.pathEngine.costsLand
        costs.terrainCost = ByteArray(blocked.size) { if (blocked[it]) -1 else 0 }
        costs.javaClass.getDeclaredMethod("buildIsolatedGroups").apply { isAccessible = true }.invoke(costs)
        costs.javaClass.getDeclaredMethod("buildAndReplaceClearanceCost", OrderableUnit::class.java)
            .apply { isAccessible = true }.invoke(costs, null)
        return MoveNavigationGrid(0f, 0f, 120, 120, 20f, 20f, blocked)
    }

    @Test
    fun anUnsafeIntermediateCannotBeSkippedEvenWhenTheFinalGoalIsDirectlyVisible() {
        val blocked = BooleanArray(30 * 30)
        for (x in 8..11) for (y in 4..7) blocked[x * 30 + y] = true
        val grid = MoveNavigationGrid(0f, 0f, 30, 30, 20f, 20f, blocked)
        val motion = builderMotion().copy(position = MovePoint(100f, 400f))
        val targets = listOf(MovePoint(200f, 100f), MovePoint(500f, 400f))
        assertTrue(grid.isClear(motion.position, targets.last(), motion.radius))
        val estimate = MoveRoutePlanner.estimateQueued(grid, motion, targets)
        assertFalse(estimate.completed, "Queued destinations must not inherit early-switch skipping")
        assertEquals(0, estimate.targetIndex)
    }

    @Test
    fun headingAndTurningStillDetermineTheFastestQueuedRoute() {
        val motion = builderMotion().copy(position = MovePoint(300f, 400f), maxTurnSpeed = 1f, turnAcceleration = -1f)
        val left = listOf(MovePoint(180f, 320f), MovePoint(180f, 180f), MovePoint(300f, 100f))
        val right = listOf(MovePoint(420f, 320f), MovePoint(420f, 180f), MovePoint(300f, 100f))
        val eastLeft = MoveRoutePlanner.estimateQueued(clearGrid(), motion, left)
        val eastRight = MoveRoutePlanner.estimateQueued(clearGrid(), motion, right)
        val westLeft = MoveRoutePlanner.estimateQueued(clearGrid(), motion.copy(headingDegrees = 180f), left)
        val westRight = MoveRoutePlanner.estimateQueued(clearGrid(), motion.copy(headingDegrees = 180f), right)
        assertTrue(listOf(eastLeft, eastRight, westLeft, westRight).all { it.completed })
        assertTrue(eastRight.time < eastLeft.time)
        assertTrue(westLeft.time < westRight.time)
    }

    @Test
    fun aRealObstacleProducesExecutableQueuedCornersWithinTheAvailableSlots() {
        val blocked = BooleanArray(30 * 30)
        for (x in 6..11) for (y in 8..15) blocked[x * 30 + y] = true
        val grid = MoveNavigationGrid(0f, 0f, 30, 30, 20f, 20f, blocked)
        val motion = builderMotion().copy(position = MovePoint(160f, 450f), headingDegrees = -90f)
        val goal = MovePoint(160f, 80f)
        val baseline = listOf(MovePoint(360f, 450f), MovePoint(360f, 80f), goal)
        val plan = assertNotNull(MoveRoutePlanner.planQueued(grid, motion, goal, baseline, 1f, 4))
        assertTrue(plan.targets.size in 2..4)
        assertEquals(goal, plan.targets.last())
        assertTrue(plan.plannedEstimatedTime < plan.nativeEstimatedTime)
        assertTrue(MoveRoutePlanner.estimateQueued(grid, motion, plan.targets, 1f, baseline, plan.terrainClearance).completed)
        assertNull(MoveRoutePlanner.planQueued(grid, motion, goal, baseline, 1f, 1))
    }

    @Test
    fun anAlreadyDirectNativeRouteKeepsTheOriginalOrder() {
        val motion = builderMotion()
        val goal = MovePoint(650f, motion.position.y)
        val native = assertNotNull(directMoveNodes(motion.position, goal))
        assertNull(MoveRoutePlanner.planQueued(clearGrid(), motion, goal, native, 1f))
    }

    @Test
    fun missingOrIncompleteNativeRoutesCannotSupplyABaseline() {
        val motion = builderMotion()
        val goal = MovePoint(650f, motion.position.y)
        val grid = clearGrid()
        assertNull(MoveRoutePlanner.planQueued(grid, motion, goal, emptyList(), 1f))
        assertNull(MoveRoutePlanner.planQueued(grid, motion, goal, listOf(MovePoint(450f, goal.y)), 1f))
    }

    @Test
    fun buildBaselineStopsAtItsRangeAndRejectsAnApparentMoveImprovement() {
        val goal = MovePoint(560f, 210f)
        val native = listOf(MovePoint(500f, 210f), MovePoint(600f, 400f), goal)
        val movePlan = assertNotNull(MoveRoutePlanner.planQueued(clearGrid(), builderMotion(), goal, native, 0f))
        val build = MoveBuildApproach(MovePoint(650f, 210f), 150f)
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        val buildPlan = MoveRoutePlanner.planQueued(clearGrid(), builderMotion(), goal, native, 0f,
            buildApproach = build, diagnostics = events::add)
        val buildBaseline = assertNotNull(events.single { it.stage == "baseline" }.estimate)
        assertTrue(buildBaseline.completed)
        assertTrue(buildBaseline.time < movePlan.plannedEstimatedTime,
            "The original BUILD is in range before the otherwise faster MOVE queue finishes")
        assertTrue(buildBaseline.distance < builderMotion().position.distanceTo(goal) - 30f,
            "BUILD must stop before the cropped approach endpoint, without moving to the building center")
        assertNull(buildPlan, "A MOVE-only baseline would falsely accept this slower BUILD approach")
        assertEquals("no-faster-queued-route", events.last().reason)
    }

    @Test
    fun enteringBuildRangeDoesNotSkipAnyOfTheInsertedMoveWaypoints() {
        val targets = listOf(MovePoint(500f, 210f), MovePoint(400f, 400f), MovePoint(560f, 210f))
        val move = MoveRoutePlanner.estimateQueued(clearGrid(), builderMotion(), targets)
        val build = MoveRoutePlanner.estimateQueued(clearGrid(), builderMotion(), targets,
            buildApproach = MoveBuildApproach(MovePoint(650f, 210f), 150f))
        assertTrue(move.completed && build.completed)
        assertEquals(targets.size, build.commands)
        assertEquals(move.distance, build.distance)
        assertEquals(move.time + 1f, build.time,
            "Retained BUILD starts after the last mandatory MOVE is popped, even if an earlier MOVE was already in range")
    }

    @Test
    fun invalidBuildRangesAndApproachPointsWithoutCompletionClearanceAreRejected() {
        val goal = MovePoint(560f, 210f)
        val native = listOf(MovePoint(500f, 210f), MovePoint(600f, 400f), goal)
        for (build in listOf(
            MoveBuildApproach(MovePoint(Float.NaN, 210f), 150f),
            MoveBuildApproach(MovePoint(650f, 210f), Float.NaN),
            MoveBuildApproach(MovePoint(650f, 210f), Float.POSITIVE_INFINITY),
            MoveBuildApproach(MovePoint(650f, 210f), 0f),
            MoveBuildApproach(MovePoint(650f, 210f), -1f),
            MoveBuildApproach(MovePoint(650f, 210f), 96f),
        )) {
            val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
            assertNull(MoveRoutePlanner.planQueued(clearGrid(), builderMotion(), goal, native, 0f,
                buildApproach = build, diagnostics = events::add))
            assertEquals("invalid-build-approach", events.last().reason)
        }
    }

    @Test
    fun capturedBuilderInputHasAValidQueuedAlternative() {
        val mask = resource("two-sides-reported-0955-mask.bin")
        val grid = MoveNavigationGrid(40f, 960f, 68, 53, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val native = resource("two-sides-reported-0955-route.csv").toString(Charsets.UTF_8)
            .lineSequence().drop(1).filter { it.isNotBlank() }.map {
                val (x, y) = it.split(','); MovePoint(x.toFloat(), y.toFloat())
            }.toList()
        val motion = builderMotion().copy(position = MovePoint(211.46997f, 1850f), speedFraction = 0.3792001f, formationLeader = true)
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        val plan = assertNotNull(MoveRoutePlanner.planQueued(grid, motion, native.last(), native, 1f, diagnostics = events::add), events.toString())
        assertEquals(native.last(), plan.targets.last())
        assertTrue(plan.targets.size in 2..MAX_ASSISTED_MOVE_TARGETS)
        assertTrue(plan.plannedEstimatedTime < plan.nativeEstimatedTime)
        assertTrue(MoveRoutePlanner.estimateQueued(grid, motion, plan.targets, 1f, native, plan.terrainClearance).completed)
    }

    @Test
    fun theNativeTileWalkCanRejectAGeometricallyClearShorelineLeg() {
        val mask = resource("two-sides-reported-0955-mask.bin")
        val grid = MoveNavigationGrid(40f, 960f, 68, 53, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val motion = builderMotion().copy(position = MovePoint(239.1261f, 1835.9917f))
        val target = MovePoint(390f, 1270f)
        assertTrue(grid.isClear(motion.position, target, 2f))
        val estimate = MoveRoutePlanner.estimateQueued(grid, motion, listOf(target), terrainClearance = 2f)
        assertFalse(estimate.completed)
        assertEquals("native-direct-blocked", estimate.failureReason)
    }

    @Test
    fun capturedInterlockedCorridorKeepsManyQueuedTurnsForASubSecondGain() {
        val mask = resource("interlocked-builder-4-mask.bin")
        val grid = MoveNavigationGrid(80f, 1040f, 53, 68, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val native = resource("interlocked-builder-4-route.csv").toString(Charsets.UTF_8)
            .lineSequence().drop(1).filter { it.isNotBlank() }.map {
                val (x, y) = it.split(','); MovePoint(x.toFloat(), y.toFloat())
            }.toList()
        val motion = builderMotion().copy(position = MovePoint(651.43195f, 2290f), speedFraction = 0.3744001f,
            formationLeader = true)
        val plan = assertNotNull(MoveRoutePlanner.planQueued(grid, motion, native.last(), native, 1f))
        assertTrue(plan.targets.size in 7..MAX_ASSISTED_MOVE_TARGETS)
        assertEquals(native.last(), plan.targets.last())
        val gain = plan.nativeEstimatedTime - plan.plannedEstimatedTime
        assertTrue(gain > 0f && gain < 60f, "Small improvements must not be filtered by the former 1s/3% gate: $gain")
        assertTrue(MoveRoutePlanner.estimateQueued(grid, motion, plan.targets, 1f, native, plan.terrainClearance).completed)
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/movement/$name")).use { it.readBytes() }

    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        fun field(type: Class<*>, name: String): Field {
            var cursor: Class<*>? = type
            while (cursor != null) {
                try { return cursor.getDeclaredField(name).apply { isAccessible = true } }
                catch (_: NoSuchFieldException) { cursor = cursor.superclass }
            }
            error("Missing $type.$name")
        }

        fun <T> withNativeEngine(block: (RWGameEngine) -> T): T {
            val singleton = field(GameEngine::class.java, "al")
            val previous = singleton.get(null)
            val dedicated = GameEngine.isDedicatedServerPlatform
            val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
            singleton.set(null, engine)
            try {
                GameEngine.isDedicatedServerPlatform = true
                engine.networkEngine = unsafe.allocateInstance(NetworkEngine::class.java) as NetworkEngine
                engine.replayEngine = unsafe.allocateInstance(ReplayEngine::class.java) as ReplayEngine
                engine.map = (unsafe.allocateInstance(NativeMap::class.java) as NativeMap).apply {
                    mapWidthInTiles = 120; mapHeightInTiles = 120
                    tileWidth = 20; tileHeight = 20; halfTileWidth = 10; halfTileHeight = 10
                    inverseTileWidth = 0.05f; inverseTileHeight = 0.05f
                    uniqueTiles = arrayOfNulls<MapTile>(1)
                    groundLayer = (unsafe.allocateInstance(MapLayer::class.java) as MapLayer).also {
                        it.map = this; it.width = 120; it.height = 120; it.tileIds = ShortArray(120 * 120)
                    }
                }
                engine.pathEngine = PathEngine().apply {
                    field(javaClass, "allowQueuedPathSolving").setBoolean(this, false)
                    setupMapCosts(engine.map, false)
                }
                return block(engine)
            } finally {
                singleton.set(null, previous)
                GameEngine.isDedicatedServerPlatform = dedicated
            }
        }
    }
}
