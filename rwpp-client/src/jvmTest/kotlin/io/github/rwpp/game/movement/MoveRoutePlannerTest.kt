package io.github.rwpp.game.movement

import kotlin.test.*

class MoveRoutePlannerTest {
    private fun grid(wall: Boolean = true): MoveNavigationGrid {
        val blocked = BooleanArray(30 * 30)
        if (wall) for (x in 6..11) for (y in 8..15) blocked[x * 30 + y] = true
        return MoveNavigationGrid(0f, 0f, 30, 30, 20f, 20f, blocked)
    }

    private fun tank(position: MovePoint = MovePoint(160f, 450f), heading: Float = -90f) = MoveMotion(
        position, heading, 0f, 0f, 1f, 4.1f, 0.25f, 0.07f, 0.17f, 0.6f, 8f,
    )

    @Test
    fun realObstacleRouteProducesFewSafeTargetsAndBeatsTheObservedLongDetour() {
        val map = grid()
        val motion = tank()
        val goal = MovePoint(160f, 80f)
        val baseline = listOf(MovePoint(360f, 450f), MovePoint(360f, 80f), goal)
        val plan = assertNotNull(MoveRoutePlanner.plan(map, motion, goal, baseline, commandDelay = 6f))
        assertEquals(goal, plan.targets.last())
        assertTrue(plan.targets.size in 2..6)
        assertTrue(plan.plannedEstimatedTime < plan.nativeEstimatedTime * 0.9f)
        var from = motion.position
        plan.targets.forEach { target ->
            assertTrue(map.isClear(from, target, plan.terrainClearance), "$from -> $target must clear blocked tiles")
            from = target
        }
    }

    @Test
    fun openGroundKeepsTheOriginalDirectOrder() {
        val goal = MovePoint(160f, 80f)
        val baseline = (430 downTo 90 step 20).map { MovePoint(160f, it.toFloat()) } + goal
        assertNull(MoveRoutePlanner.plan(grid(false), tank(), goal, baseline, 6f))
    }

    @Test
    fun headingChangesTravelTimeEvenForEqualLengthRoutes() {
        val blocked = BooleanArray(30 * 30)
        for (x in 13..16) for (y in 10..14) blocked[x * 30 + y] = true
        val map = MoveNavigationGrid(0f, 0f, 30, 30, 20f, 20f, blocked)
        val goal = MovePoint(300f, 100f)
        val start = MovePoint(300f, 400f)
        val left = listOf(MovePoint(180f, 320f), MovePoint(180f, 180f), goal)
        val right = listOf(MovePoint(420f, 320f), MovePoint(420f, 180f), goal)
        val east = tank(start, 0f).copy(maxTurnSpeed = 1f, turnAcceleration = -1f)
        val west = east.copy(headingDegrees = 180f)
        val eastLeft = MoveRoutePlanner.estimate(map, east, left, true, 4f)
        val eastRight = MoveRoutePlanner.estimate(map, east, right, true, 4f)
        val westLeft = MoveRoutePlanner.estimate(map, west, left, true, 4f)
        val westRight = MoveRoutePlanner.estimate(map, west, right, true, 4f)
        assertTrue(eastLeft.completed && eastRight.completed && westLeft.completed && westRight.completed,
            "eastLeft=$eastLeft eastRight=$eastRight westLeft=$westLeft westRight=$westRight")
        assertTrue(eastRight.time < eastLeft.time, "Facing east must favor the right-hand route")
        assertTrue(westLeft.time < westRight.time, "Facing west must favor the left-hand route")
    }

    @Test
    fun anEarlySwitchCannotCutAcrossAnObstacle() {
        val blocked = BooleanArray(30 * 30)
        for (x in 5..9) for (y in 5..9) blocked[x * 30 + y] = true
        val map = MoveNavigationGrid(0f, 0f, 30, 30, 20f, 20f, blocked)
        val targets = listOf(MovePoint(220f, 70f), MovePoint(80f, 70f))
        assertEquals(0, MoveRoutePlanner.nextTarget(map, tank(MovePoint(220f, 220f)), targets, 0, 6f))
        assertEquals(1, MoveRoutePlanner.nextTarget(map, tank(MovePoint(220f, 70f)), targets, 0, 6f))
    }

    @Test
    fun aLongCommandDelayStillChecksTheTurnAfterTheCommandTakesEffect() {
        val blocked = BooleanArray(60 * 60)
        for (x in 24..26) for (y in 18..22) blocked[x * 60 + y] = true
        val map = MoveNavigationGrid(0f, 0f, 60, 60, 10f, 10f, blocked)
        val motion = tank(MovePoint(100f, 200f), 0f).copy(
            speedFraction = 1f, maxTurnSpeed = 0.5f, turnAcceleration = -1f,
            acceleration = 0.01f, deceleration = 0.01f, radius = 5f,
        )
        val targets = listOf(MovePoint(400f, 200f), MovePoint(220f, 100f))
        assertTrue(map.isClear(motion.position, targets.last(), motion.radius))
        assertEquals(1, MoveRoutePlanner.nextTarget(map, motion, targets, 0, 0f))
        assertEquals(0, MoveRoutePlanner.nextTarget(map, motion, targets, 0, 120f),
            "Waiting may consume simulation steps, but may not replace validation of the subsequent turn")
    }

    @Test
    fun invalidOrIncompleteNativeBaselinesDoNotProduceCommands() {
        val goal = MovePoint(160f, 80f)
        val map = grid()
        assertNull(MoveRoutePlanner.plan(map, tank(), goal, emptyList(), 0f))
        assertNull(MoveRoutePlanner.plan(map, tank(), goal, listOf(MovePoint(300f, 100f)), 0f))
        assertNull(MoveRoutePlanner.plan(map, tank(), goal, listOf(goal), Float.NaN))
        assertNull(MoveRoutePlanner.plan(map, tank(), goal, listOf(goal), 121f))
        assertNull(MoveRoutePlanner.plan(map, tank().copy(maxSpeed = 0f), goal, listOf(goal), 0f))
    }

    @Test
    fun anEnclosedGoalOrInsufficientClearanceFallsBack() {
        val map = grid()
        val insideObstacle = MovePoint(180f, 220f)
        assertNull(MoveRoutePlanner.plan(map, tank(), insideObstacle, listOf(insideObstacle), 0f))
        val boundaryGoal = MovePoint(1f, 100f)
        assertNull(MoveRoutePlanner.plan(map, tank(), boundaryGoal, listOf(boundaryGoal), 0f))
    }
}
