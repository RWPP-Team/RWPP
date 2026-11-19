package io.github.rwpp.game.movement

import kotlin.test.*

/** Replays navigation input from reported games without a live game or user save. */
class MoveReportedMapTest {
    private fun resource(name: String): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/movement/$name"),
    ).use { it.readBytes() }

    private fun map(): MoveNavigationGrid {
        val terrain = resource("two-sides-land-cost.bin")
        assertEquals(120 * 120, terrain.size)
        // Same bounded capture as NativeMoveTerrain for this start/goal/native route.
        val width = 69
        val height = 53
        val blocked = BooleanArray(width * height) { index ->
            terrain[(index / height + 1) * 120 + index % height + 48].toInt() == -1
        }
        return MoveNavigationGrid(20f, 960f, width, height, 20f, 20f, blocked)
    }

    private val goal = MovePoint(1234f, 1131f)
    private val motion = MoveMotion(MovePoint(210f, 1850f), 0f, 0f, 0f,
        0.8f, 7f, 0.5f, 0.07f, 0.07f, 1f, 13f)

    private fun route(name: String) = resource(name).toString(Charsets.UTF_8)
        .lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val (x, y) = line.split(',')
            MovePoint(x.toFloat(), y.toFloat())
        }.toList()

    private fun nativeRoute() = route("two-sides-native-route.csv")

    private fun loggedRoute() = route("two-sides-reported-0101-route.csv")

    private fun loggedMap(): MoveNavigationGrid {
        val terrain = resource("two-sides-land-cost.bin")
        val width = 67
        val height = 53
        val blocked = BooleanArray(width * height) { index ->
            val x = index / height + 2
            val y = index % height + 48
            // Stock team-2 CommandCenter at (210,1790), native inclusive [-1,-1..1,1].
            terrain[x * 120 + y].toInt() == -1 || x in 9..11 && y in 88..90
        }
        return MoveNavigationGrid(40f, 960f, width, height, 20f, 20f, blocked)
    }

    private fun loggedMotion() = MoveMotion(MovePoint(211.47769f, 1850f), 0f, 0.37920013f, 0f,
        0.8f, 3.8f, 0.35f, 0.04f, 0.1f, 0.6f, 10f, formationLeader = true)

    @Test
    fun loggedCommandCenterCornerDoesNotInvalidateTheNativeBaseline() {
        val grid = loggedMap()
        val before = MovePoint(244.62488f, 1829.3008f)
        val after = MovePoint(244.83951f, 1828.5302f)
        assertTrue(grid.isClear(before, after, 0f), "The native center remains outside the building footprint")
        assertFalse(grid.isClear(before, after, 10f), "This is the exact old tick-56 rejection")
        val baseline = MoveRoutePlanner.estimate(grid, loggedMotion(), loggedRoute(), false, 0f)
        assertTrue(baseline.completed, baseline.toString())
    }

    @Test
    fun loggedNativeInputKeepsTheCorridorExitAndProducesClearTargets() {
        val grid = loggedMap()
        val body = loggedMotion()
        val reportedGoal = MovePoint(1200.6053f, 1129.3683f)
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        val plan = assertNotNull(MoveRoutePlanner.plan(grid, body, reportedGoal, loggedRoute(), 1.14f, events::add))
        assertEquals(reportedGoal, plan.targets.last())
        assertFalse(sameMoveTarget(plan.targets.first(), reportedGoal))
        assertTrue(plan.nativeEstimatedTime - plan.plannedEstimatedTime >= 60f)
        assertEquals("planned", events.last().reason)
        assertTrue(grid.corners(body.position, reportedGoal, body.radius).size > 64,
            "The old ranking discarded the corridor exit in this fixture")
        var previous = body.position
        plan.targets.forEach { target ->
            assertTrue(grid.isClear(previous, target, plan.terrainClearance))
            previous = target
        }
    }

    @Test
    fun rejectingAnUnsafeContinuationDoesNotDiscardAUsableAlternative() {
        val grid = map()
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        val plan = assertNotNull(MoveRoutePlanner.plan(grid, motion, goal, nativeRoute(), 6f, events::add))
        val rejected = events.filter {
            it.stage == "candidate" && it.reason == "no-safe-continuation" && it.terrainClearance == plan.terrainClearance
        }
        assertTrue(rejected.isNotEmpty())
        assertTrue(rejected.none { it.targets == plan.targets })
        assertEquals("planned", events.last().reason)
        assertEquals(motion, events.first().input?.motion)
        assertEquals(nativeRoute(), events.first().input?.nativeRoute)
    }

    @Test
    fun reportedShorelinePositionCanTakeANativeClearLegWithoutCuttingBlockedTiles() {
        // From the second Builder's real movement snapshots, next to blocked tile (23,61).
        val map = loggedMap()
        val body = loggedMotion().copy(position = MovePoint(451.5966f, 1235.7926f),
            headingDegrees = -86.5677f, speedFraction = 1f, currentTurnRate = -2.961f)
        val next = MovePoint(450f, 1210f)
        assertFalse(MoveRoutePlanner.canApproachTarget(map, body, next, body.radius))
        assertTrue(MoveRoutePlanner.canApproachTarget(map, body, next, 2f))
        assertFalse(MoveRoutePlanner.canApproachTarget(map, body, MovePoint(470f, 1230f), 2f))
    }

    @Test
    fun loggedShorelinePlanChecksTheGamesTileTraversalBeforeSwitching() {
        val mask = resource("two-sides-reported-0955-mask.bin")
        val map = MoveNavigationGrid(40f, 960f, 68, 53, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val body = loggedMotion().copy(position = MovePoint(211.46997f, 1850f), speedFraction = 0.3792001f)
        val native = route("two-sides-reported-0955-route.csv")
        val goal = native.last()
        val plan = assertNotNull(MoveRoutePlanner.plan(map, body, goal, native, 1f))
        assertEquals(2f, plan.terrainClearance)
        val gain = plan.nativeEstimatedTime - plan.plannedEstimatedTime
        assertTrue(gain > 0f)
        assertTrue(gain < plan.nativeEstimatedTime * 0.10f)

        // The game reprobed this attempted shortcut from a neighboring tile and sent it to A*.
        val earlyTurn = MovePoint(239.1261f, 1835.9917f)
        val next = MovePoint(390f, 1270f)
        assertTrue(map.isClear(earlyTurn, next, plan.terrainClearance))
        assertFalse(map.nativeDirectTilesClear(earlyTurn, next))
        assertFalse(MoveRoutePlanner.canApproachTarget(map, body.copy(position = earlyTurn), next, plan.terrainClearance))
    }

    @Test
    fun anAlternateNativeAnchorRecoversTheThirdBuildersInitiallyRejectedRoute() {
        val mask = resource("two-sides-builder-21-mask.bin")
        val map = MoveNavigationGrid(40f, 960f, 68, 54, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val body = loggedMotion().copy(position = MovePoint(209.00597f, 1876.9663f), headingDegrees = 74.311325f,
            speedFraction = 0f, currentTurnRate = -3.3600001f)
        val native = route("two-sides-builder-21-route.csv")
        val diagnostics = mutableListOf<MoveRoutePlanner.Diagnostic>()
        val plan = assertNotNull(MoveRoutePlanner.plan(map, body, native.last(), native, 1f, diagnostics::add))
        assertTrue(diagnostics.any { it.reason == "no-safe-continuation" })
        assertEquals(native.last(), plan.targets.last())
        assertTrue(plan.nativeEstimatedTime - plan.plannedEstimatedTime >= 60f)
        assertTrue((listOf(body.position) + plan.targets).zipWithNext().all { (from, to) ->
            map.nativeDirectTilesClear(from, to) && map.isClear(from, to, plan.terrainClearance)
        })
    }

    @Test
    fun interlockedCorridorAdmitsMoreThanSixTargetsForASubSecondGain() {
        val mask = resource("interlocked-builder-4-mask.bin")
        val grid = MoveNavigationGrid(80f, 1040f, 53, 68, 20f, 20f, BooleanArray(mask.size) { mask[it] != 0.toByte() })
        val motion = loggedMotion().copy(position = MovePoint(651.43195f, 2290f), speedFraction = 0.3744001f)
        val native = route("interlocked-builder-4-route.csv")
        val plan = assertNotNull(MoveRoutePlanner.plan(grid, motion, native.last(), native, 1f))
        assertEquals(native.last(), plan.targets.last())
        assertTrue(plan.targets.size in 7..MAX_ASSISTED_MOVE_TARGETS)
        val gain = plan.nativeEstimatedTime - plan.plannedEstimatedTime
        assertTrue(gain > 0f && gain < 60f && gain < plan.nativeEstimatedTime * 0.03f, "Gain was $gain steps")
        assertTrue((listOf(motion.position) + plan.targets).zipWithNext().all { (from, to) ->
            grid.nativeDirectTilesClear(from, to) && grid.isClear(from, to, plan.terrainClearance)
        })
    }

    @Test
    fun blockedLavaAndRidgeCellsAreNotTreatedAsAnOrdinaryClearShortcut() {
        val grid = map()
        assertFalse(grid.isClear(MovePoint(722f, 1490.5f), MovePoint(722f, 1490.5f), 0f))
        assertFalse(grid.isClear(MovePoint(1070f, 1230f), MovePoint(1070f, 1230f), 0f))
        assertFalse(grid.isClear(motion.position, goal, motion.radius))
        assertTrue(grid.isClear(goal, goal, motion.radius))
    }

    @Test
    fun aBaselineCollisionHasItsOwnFailureAndFirstRejectedSegment() {
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        assertNull(MoveRoutePlanner.plan(map(), motion, goal, listOf(goal), 0f, events::add))
        assertEquals("baseline-collision", events.last().reason)
        val baseline = events.first { it.stage == "baseline" }.estimate!!
        assertEquals("collision", baseline.failureReason)
        assertNotNull(baseline.collisionFrom)
        assertNotNull(baseline.collisionTo)
    }

    @Test
    fun malformedNativeRouteIsReportedBeforeCandidateScoring() {
        val events = mutableListOf<MoveRoutePlanner.Diagnostic>()
        assertNull(MoveRoutePlanner.plan(map(), motion, goal, emptyList(), 0f, events::add))
        assertEquals("invalid-input", events.last().reason)
        assertFalse(events.any { it.stage == "baseline" })
    }
}
