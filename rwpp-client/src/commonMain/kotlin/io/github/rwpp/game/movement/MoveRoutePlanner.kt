/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import java.util.PriorityQueue
import kotlin.math.*

/** The existing BUILD can finish approaching as soon as its center is within this range. */
internal data class MoveBuildApproach(val center: MovePoint, val range: Float)

/**
 * Searches a small visibility graph, then ranks routes with a bounded movement prediction.
 * Predictions are a conservative comparison aid, not a claim of globally optimal native motion.
 */
internal object MoveRoutePlanner {
    private const val MAX_SIMULATION_TIME = 6000
    private const val MOVE_COMPLETION_RADIUS = 7f
    private const val MAX_NATIVE_RECHECKS = 4
    private const val MAX_ALTERNATE_LEGS = 12
    private const val NEAR_TIE_STEPS = 30f

    private data class Candidate(val targets: List<MovePoint>, val clearance: Float, val estimate: Estimate)

    /** Plans mandatory native MOVE waypoints. Unlike [plan], no target is switched early or skipped. */
    fun planQueued(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        goal: MovePoint,
        nativeRoute: List<MovePoint>,
        commandDelay: Float,
        maxTargets: Int = MAX_ASSISTED_MOVE_TARGETS,
        buildApproach: MoveBuildApproach? = null,
        diagnostics: ((Diagnostic) -> Unit)? = null,
    ): MoveRoutePlan? {
        fun report(stage: String, reason: String, targets: List<MovePoint> = emptyList(), prediction: Estimate? = null,
                   clearance: Float? = null) = diagnostics?.invoke(Diagnostic(stage, reason, targets, prediction,
            terrainClearance = clearance))
        diagnostics?.invoke(Diagnostic("input", "queued-snapshot", input = InputSnapshot(
            motion, goal, commandDelay, nativeRoute.toList(), MovePoint(grid.originX, grid.originY),
            grid.width, grid.height, grid.tileWidth, grid.tileHeight, grid.blockedMaskHex(),
        )))
        if (!motion.valid || !goal.finite || !commandDelay.isFinite() || commandDelay !in 0f..120f ||
            maxTargets !in 2..MAX_ASSISTED_MOVE_TARGETS || nativeRoute.isEmpty() || nativeRoute.size > 512 ||
            nativeRoute.any { !it.finite } || nativeRoute.last().distanceTo(goal) > 2f ||
            motion.position.distanceTo(goal) < 40f) {
            report("result", "invalid-input")
            return null
        }
        if (buildApproach != null && (!buildApproach.center.finite || !buildApproach.range.isFinite() ||
                buildApproach.range <= MOVE_COMPLETION_RADIUS ||
                goal.distanceTo(buildApproach.center) > buildApproach.range - MOVE_COMPLETION_RADIUS)) {
            // Native MOVE may finish up to 7px before its target; BUILD must already be in range there.
            report("result", "invalid-build-approach")
            return null
        }
        val closeClearance = min(motion.radius, min(2f, min(grid.tileWidth, grid.tileHeight) * 0.10f))
        if (!grid.isClear(motion.position, motion.position, closeClearance) || !grid.isClear(goal, goal, closeClearance)) {
            report("result", "endpoints-not-clear")
            return null
        }
        val baseline = simulateQueued(grid, motion, nativeRoute, 0f, emptyList(), 0f, 0,
            nativeBaseline = true, buildApproach = buildApproach)
        report("baseline", if (baseline.completed) "completed" else baseline.failureReason ?: "incomplete", nativeRoute, baseline)
        if (!baseline.completed) {
            report("result", "baseline-${baseline.failureReason ?: "incomplete"}")
            return null
        }
        val candidates = ArrayList<Candidate>()
        for (clearance in listOf(motion.radius, closeClearance).distinct()) {
            if (!grid.isClear(motion.position, motion.position, clearance) || !grid.isClear(goal, goal, clearance)) continue
            val corners = grid.corners(motion.position, goal, clearance)
            report("corners", "selected", corners, clearance = clearance)
            val nodes = listOf(motion.position, goal) + corners
            val edges = FloatArray(nodes.size * nodes.size) { Float.NaN }
            val routes = LinkedHashSet<List<MovePoint>>()
            for (weight in floatArrayOf(0.35f, 1f, 2.5f)) {
                search(grid, motion, nodes, edges, weight, clearance, maxTargets)?.let(routes::add)
            }
            val shortcuts = NativeRouteShortcuts(grid, motion.position, nativeRoute, clearance)
            shortcuts.simplify(maxTargets = maxTargets)?.let { route ->
                routes.add(route)
                val alternateCount = min(route.lastIndex, MAX_ALTERNATE_LEGS)
                for (sample in 0 until alternateCount) {
                    val leg = if (alternateCount == 1) 0 else sample * (route.lastIndex - 1) / (alternateCount - 1)
                    for (skip in 1..2) shortcuts.simplify(leg, skip, maxTargets)?.let(routes::add)
                }
            }
            if (grid.isClear(motion.position, goal, clearance)) {
                routes.add(listOf(MovePoint((motion.position.x + goal.x) / 2f, (motion.position.y + goal.y) / 2f), goal))
            }
            for (targets in routes) {
                // One unchanged MOVE merely asks the native engine for the same final path.
                if (targets.size !in 2..maxTargets || targets.last() != goal) continue
                val prediction = estimateQueued(grid, motion, targets, commandDelay, nativeRoute, clearance,
                    firstIssueWait = 7, buildApproach = buildApproach)
                if (prediction.completed && prediction.time < baseline.time) candidates.add(Candidate(targets, clearance, prediction))
                else report("candidate", prediction.failureReason ?: "not-faster", targets, prediction, clearance)
            }
        }
        val verified = ArrayList<Candidate>()
        for (candidate in candidates.sortedBy { it.estimate.time }.take(MAX_NATIVE_RECHECKS)) {
            var slowest = candidate.estimate
            var valid = true
            for (wait in intArrayOf(0, 4)) {
                val replay = estimateQueued(grid, motion, candidate.targets, commandDelay, nativeRoute,
                    candidate.clearance, wait, buildApproach)
                if (!replay.completed || replay.time >= baseline.time) { valid = false; break }
                if (replay.time > slowest.time) slowest = replay
            }
            if (valid) verified.add(candidate.copy(estimate = slowest))
        }
        val fastest = verified.minOfOrNull { it.estimate.time }
        val selected = fastest?.let { time -> verified.filter { it.estimate.time <= time + NEAR_TIE_STEPS }
            .minWithOrNull(compareBy<Candidate> { it.targets.size }.thenBy { it.estimate.time }) }
        if (selected == null) { report("result", "no-faster-queued-route"); return null }
        report("candidate", "accepted-queued", selected.targets, selected.estimate, selected.clearance)
        report("result", "planned", selected.targets, selected.estimate, selected.clearance)
        return MoveRoutePlan(selected.targets, baseline.time, selected.estimate.time, selected.clearance)
    }

    /** Prediction for actual queued destinations, including native path construction and waypoint handoff frames. */
    internal fun estimateQueued(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        targets: List<MovePoint>,
        commandDelay: Float = 0f,
        nativePrefix: List<MovePoint> = emptyList(),
        terrainClearance: Float = motion.radius,
        firstIssueWait: Int = 0,
        buildApproach: MoveBuildApproach? = null,
    ): Estimate = simulateQueued(grid, motion, targets, commandDelay, nativePrefix, terrainClearance, firstIssueWait,
        false, buildApproach)

    private fun simulateQueued(
        grid: MoveNavigationGrid, motion: MoveMotion, targets: List<MovePoint>, delay: Float,
        nativePrefix: List<MovePoint>, clearance: Float, firstIssueWait: Int, nativeBaseline: Boolean,
        buildApproach: MoveBuildApproach? = null,
    ): Estimate {
        if (!motion.valid || targets.isEmpty() || targets.any { !it.finite } || !delay.isFinite() ||
            delay !in 0f..120f || !clearance.isFinite() || clearance < 0f || firstIssueWait !in 0..120 ||
            nativePrefix.any { !it.finite } ||
            (buildApproach != null && (!buildApproach.center.finite || !buildApproach.range.isFinite() ||
                buildApproach.range <= MOVE_COMPLETION_RADIUS ||
                targets.last().distanceTo(buildApproach.center) > buildApproach.range - MOVE_COMPLETION_RADIUS))) {
            return Estimate(false, 0f, 0f, 0f, 0f, 0, "invalid-input")
        }
        val v = Vehicle(motion)
        val activateAt = if (nativePrefix.isNotEmpty()) ceil(delay).toInt() + firstIssueWait else 0
        var targetIndex = 0
        var prefixIndex = 0
        var nodes: List<MovePoint>? = null
        var nodeIndex = 0
        var distance = 0f
        var stopped = 0f
        var reverse = 0f
        for (time in 0 until MAX_SIMULATION_TIME) {
            val active = nativeBaseline || time >= activateAt
            val before = v.position
            val nativeNodes = nativeBaseline || !active
            if (nativeNodes) {
                val route = if (nativeBaseline) targets else nativePrefix
                var cursor = if (nativeBaseline) targetIndex else prefixIndex
                val nativeFinished = if (buildApproach != null) before.distanceTo(buildApproach.center) <= buildApproach.range
                    else cursor == route.lastIndex && before.distanceTo(route.last()) < MOVE_COMPLETION_RADIUS
                if (nativeFinished) {
                    if (nativeBaseline) return Estimate(true, time.toFloat(), distance, stopped, reverse, 0)
                    v.brake()
                } else {
                    if (motion.maxSpeed > 5f && cursor < route.lastIndex && before.distanceTo(route[cursor + 1]) < 19f) cursor++
                    val target = route[cursor]
                    // A cropped BUILD approach node is not the native path's final MOVE destination.
                    v.step(target, buildApproach == null && cursor == route.lastIndex, route.size - cursor)
                    // Native updateMoveThrottle steers at this node before consuming it.
                    if (cursor < route.lastIndex && before.distanceTo(target) < 16f) cursor++
                    if (nativeBaseline) targetIndex = cursor else prefixIndex = cursor
                }
            } else if (before.distanceTo(targets[targetIndex]) < MOVE_COMPLETION_RADIUS) {
                if (targetIndex == targets.lastIndex) {
                    // The retained BUILD runs on the frame after the last MOVE is popped, never at an earlier corner.
                    return Estimate(true, time + if (buildApproach == null) 0f else 1f, distance, stopped, reverse, targets.size)
                }
                targetIndex++
                nodes = null
                // popWaypoint makes this frame's movement directive empty; it does not reset throttle.
                v.brake()
            } else if (nodes == null) {
                val target = targets[targetIndex]
                if (!grid.nativeDirectTilesClear(before, target) || !grid.isClear(before, target, clearance)) {
                    return Estimate(false, time.toFloat(), distance, stopped, reverse, targets.size,
                        "native-direct-blocked", targetIndex)
                }
                nodes = directMoveNodes(before, target) ?: return Estimate(false, time.toFloat(), distance, stopped, reverse,
                    targets.size, "native-direct-too-long", targetIndex)
                nodeIndex = 0
                // requestPathTo fills pathNodes after the update's local first-node lookup.
                v.brake()
            } else {
                val leg = nodes
                if (motion.maxSpeed > 5f && nodeIndex < leg.lastIndex && before.distanceTo(leg[nodeIndex + 1]) < 19f) nodeIndex++
                val target = leg[nodeIndex]
                v.step(target, nodeIndex == leg.lastIndex, leg.size - nodeIndex)
                if (nodeIndex < leg.lastIndex && before.distanceTo(target) < 16f) nodeIndex++
            }
            if (!grid.isClear(before, v.position, if (nativeNodes) 0f else clearance)) {
                return Estimate(false, time.toFloat(), distance, stopped, reverse, if (nativeBaseline) 0 else targets.size,
                    "collision", targetIndex, before, v.position)
            }
            val moved = before.distanceTo(v.position)
            distance += moved
            if (abs(v.speed) < 0.03f) stopped++
            if (v.speed < 0f) reverse += moved
        }
        return Estimate(false, MAX_SIMULATION_TIME.toFloat(), distance, stopped, reverse, targets.size, "timeout", targetIndex)
    }

    fun plan(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        goal: MovePoint,
        nativeRoute: List<MovePoint>,
        commandDelay: Float,
        diagnostics: ((Diagnostic) -> Unit)? = null,
    ): MoveRoutePlan? {
        fun report(stage: String, reason: String, targets: List<MovePoint> = emptyList(), estimate: Estimate? = null,
                   benefit: Float? = null, requiredBenefit: Float? = null, clearance: Float? = null) {
            diagnostics?.invoke(Diagnostic(stage, reason, targets.toList(), estimate, benefit, requiredBenefit,
                terrainClearance = clearance))
        }
        diagnostics?.invoke(Diagnostic("input", "snapshot", input = InputSnapshot(
            motion, goal, commandDelay, nativeRoute.toList(), MovePoint(grid.originX, grid.originY),
            grid.width, grid.height, grid.tileWidth, grid.tileHeight, grid.blockedMaskHex(),
        )))
        if (!motion.valid || !goal.finite || !commandDelay.isFinite() || commandDelay !in 0f..120f ||
            nativeRoute.isEmpty() || nativeRoute.size > 512 || nativeRoute.any { !it.finite } ||
            nativeRoute.last().distanceTo(goal) > 2f || motion.position.distanceTo(goal) < 40f) {
            report("result", "invalid-input")
            return null
        }
        // Native terrain and building footprints block the unit's center. Keep a small
        // margin for prediction error and compare against the existing wider routes.
        val closeClearance = min(motion.radius, min(2f, min(grid.tileWidth, grid.tileHeight) * 0.10f))
        if (!grid.isClear(motion.position, motion.position, closeClearance)) {
            report("result", "start-not-clear")
            return null
        }
        if (!grid.isClear(goal, goal, closeClearance)) {
            report("result", "goal-not-clear")
            return null
        }

        val baseline = estimate(grid, motion, nativeRoute, guided = false, commandDelay = 0f)
        report("baseline", if (baseline.completed) "completed" else baseline.failureReason ?: "incomplete", nativeRoute, baseline)
        if (!baseline.completed) {
            report("result", "baseline-${baseline.failureReason ?: "incomplete"}", estimate = baseline)
            return null
        }
        var eligibleCandidates = 0
        var completedCandidates = 0
        val candidatesToCheck = ArrayList<Candidate>()
        val retryCloser = LinkedHashSet<List<MovePoint>>()
        val evaluatedClose = HashSet<List<MovePoint>>()
        fun evaluate(candidates: Iterable<List<MovePoint>>, clearance: Float) {
            for (targets in candidates.distinct()) {
                if (clearance == closeClearance && !evaluatedClose.add(targets)) continue
                if (targets.size !in 2..MAX_ASSISTED_MOVE_TARGETS || targets.last() != goal) {
                    report("candidate", "invalid-targets", targets, clearance = clearance)
                    continue
                }
                eligibleCandidates++
                val prediction = estimateWithClearance(grid, motion, targets, true, commandDelay, nativeRoute, clearance)
                if (!prediction.completed) {
                    if (clearance > closeClearance && prediction.failureReason == "no-safe-continuation") retryCloser.add(targets)
                    report("candidate", prediction.failureReason ?: "incomplete", targets, prediction, clearance = clearance)
                    continue
                }
                completedCandidates++
                val benefit = baseline.time - prediction.time
                if (benefit <= 0f) {
                    report("candidate", "insufficient-benefit", targets, prediction, benefit, 0f, clearance)
                    continue
                }
                candidatesToCheck.add(Candidate(targets.toList(), clearance, prediction))
            }
        }
        for (clearance in listOf(motion.radius, closeClearance).distinct()) {
            if (!grid.isClear(motion.position, motion.position, clearance) ||
                !grid.isClear(goal, goal, clearance)) continue
            val corners = grid.corners(motion.position, goal, clearance)
            report("corners", "selected", corners, clearance = clearance)
            val nodes = listOf(motion.position, goal) + corners
            val candidates = ArrayList<List<MovePoint>>()
            val edges = FloatArray(nodes.size * nodes.size) { Float.NaN }
            for (weight in floatArrayOf(0.35f, 1f, 2.5f)) {
                search(grid, motion, nodes, edges, weight, clearance)?.let(candidates::add)
            }
            val shortcuts = NativeRouteShortcuts(grid, motion.position, nativeRoute, clearance)
            val directCuts = shortcuts.simplify()
            if (directCuts != null) {
                candidates.add(directCuts)
                // The furthest visible waypoint can produce a bad turn or an unsafe continuation
                // when the game finishes the intermediate MOVE early. Try nearby earlier anchors
                // with the same preceding legs and recompute the suffix.
                val alternateCount = min(directCuts.lastIndex, MAX_ALTERNATE_LEGS)
                for (sample in 0 until alternateCount) {
                    val leg = if (alternateCount == 1) 0 else sample * (directCuts.lastIndex - 1) / (alternateCount - 1)
                    for (skip in 1..2) {
                        shortcuts.simplify(leg, skip)?.let(candidates::add)
                    }
                }
            }
            if (grid.isClear(motion.position, goal, clearance)) {
                // Reissuing the unchanged final goal alone cannot alter its native path.
                candidates.add(listOf(MovePoint((motion.position.x + goal.x) / 2, (motion.position.y + goal.y) / 2), goal))
            }
            evaluate(candidates, clearance)
        }
        // Reuse wide candidates with the closer checks even when another route has a small gain.
        evaluate(retryCloser, closeClearance)
        val verified = ArrayList<Candidate>()
        val shortlist = candidatesToCheck.sortedWith(compareBy<Candidate> { it.estimate.time }.thenBy { it.estimate.commands })
        for (candidate in shortlist.take(MAX_NATIVE_RECHECKS)) {
            var slowest: Estimate? = null
            for (firstIssueWait in intArrayOf(0, 4, 7)) {
                val replay = estimateWithClearance(grid, motion, candidate.targets, true, commandDelay, nativeRoute,
                    candidate.clearance, followDirectNodes = true, firstIssueWait = firstIssueWait)
                if (!replay.completed || replay.time >= baseline.time) {
                    report("candidate", "native-model-" + (replay.failureReason ?: "not-faster"),
                        candidate.targets, replay, baseline.time - replay.time, 0f, candidate.clearance)
                    slowest = null
                    break
                }
                if (replay.time > (slowest?.time ?: -1f)) slowest = replay
            }
            if (slowest != null) verified.add(candidate.copy(estimate = slowest))
        }
        val fastest = verified.minOfOrNull { it.estimate.time }
        val selected = fastest?.let { time ->
            verified.filter { it.estimate.time <= time + NEAR_TIE_STEPS }
                .minWithOrNull(compareBy<Candidate> { it.estimate.commands }.thenBy { it.estimate.time })
        }
        val best = selected?.let {
            report("candidate", "accepted", it.targets, it.estimate, baseline.time - it.estimate.time, 0f, it.clearance)
            MoveRoutePlan(it.targets, baseline.time, it.estimate.time, it.clearance)
        }
        report("result", when {
            best != null -> "planned"
            eligibleCandidates == 0 -> "no-candidate"
            completedCandidates == 0 -> "no-safe-candidate"
            else -> "insufficient-benefit"
        }, clearance = best?.terrainClearance)
        return best
    }

    internal data class Diagnostic(
        val stage: String,
        val reason: String,
        val targets: List<MovePoint> = emptyList(),
        val estimate: Estimate? = null,
        val benefit: Float? = null,
        val requiredBenefit: Float? = null,
        val input: InputSnapshot? = null,
        val terrainClearance: Float? = null,
    )

    internal data class InputSnapshot(
        val motion: MoveMotion,
        val goal: MovePoint,
        val commandDelay: Float,
        val nativeRoute: List<MovePoint>,
        val gridOrigin: MovePoint,
        val gridWidth: Int,
        val gridHeight: Int,
        val tileWidth: Float,
        val tileHeight: Float,
        val blockedCellMaskHex: String,
    )

    /** Returns a later target only when both the straight segment and predicted turn are clear. */
    @JvmOverloads
    fun nextTarget(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        targets: List<MovePoint>,
        activeIndex: Int,
        commandDelay: Float,
        terrainClearance: Float = motion.radius,
        intermediateReached: Boolean = false,
    ): Int {
        if (!motion.valid || activeIndex !in targets.indices || commandDelay !in 0f..120f ||
            targets.any { !it.finite }) return activeIndex
        val vehicle = Vehicle(motion)
        return chooseTarget(grid, vehicle, targets, activeIndex, commandDelay, terrainClearance, intermediateReached)
    }

    fun canApproachTarget(grid: MoveNavigationGrid, motion: MoveMotion, target: MovePoint, terrainClearance: Float): Boolean =
        motion.valid && canTurnTowards(grid, Vehicle(motion), target, null, 0, terrainClearance)

    internal data class Estimate(
        val completed: Boolean,
        val time: Float,
        val distance: Float,
        val stoppedTime: Float,
        val reverseDistance: Float,
        val commands: Int,
        val failureReason: String? = null,
        val targetIndex: Int? = null,
        val collisionFrom: MovePoint? = null,
        val collisionTo: MovePoint? = null,
    )

    /** A one-step calibration seam for comparison with the actual game controller. */
    internal fun predictStep(motion: MoveMotion, target: MovePoint, terminal: Boolean): MoveMotion {
        val vehicle = Vehicle(motion)
        vehicle.step(target, terminal)
        return motion.copy(position = vehicle.position, headingDegrees = vehicle.heading,
            speedFraction = vehicle.speed, currentTurnRate = vehicle.turnRate)
    }

    /** Uses the same selection policy as the runtime; queued MOVE stops are never smoothed away. */
    internal fun estimate(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        targets: List<MovePoint>,
        guided: Boolean,
        commandDelay: Float,
        nativePrefix: List<MovePoint> = emptyList(),
    ): Estimate = estimateWithClearance(grid, motion, targets, guided, commandDelay, nativePrefix, motion.radius)

    private fun estimateWithClearance(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        targets: List<MovePoint>,
        guided: Boolean,
        commandDelay: Float,
        nativePrefix: List<MovePoint>,
        terrainClearance: Float,
        followDirectNodes: Boolean = false,
        firstIssueWait: Int = 0,
    ): Estimate {
        if (!motion.valid || targets.isEmpty() || targets.any { !it.finite } ||
            !commandDelay.isFinite() || commandDelay !in 0f..120f) return Estimate(false, 0f, 0f, 0f, 0f, 0,
            failureReason = "invalid-input")
        val v = Vehicle(motion)
        var index = 0
        var prefixIndex = 0
        val activationTime = if (guided && nativePrefix.isNotEmpty()) ceil(commandDelay).toInt() + firstIssueWait else 0
        var active = activationTime == 0
        var directNodes: List<MovePoint> = emptyList()
        var directNodeIndex = 0
        var pendingIndex = -1
        var pendingAt = 0
        var commands = if (guided) 1 else 0
        var distance = 0f
        var stopped = 0f
        var reverse = 0f
        var lastCommandTime = 0
        var intermediateReached = false
        for (time in 0 until MAX_SIMULATION_TIME) {
            if (!active && time >= activationTime) active = true
            var commandApplied = guided && time == activationTime
            if (pendingIndex >= 0 && time >= pendingAt) {
                index = pendingIndex
                pendingIndex = -1
                intermediateReached = false
                commandApplied = true
            }
            if (commandApplied && (followDirectNodes || time == activationTime)) {
                if (!grid.nativeDirectTilesClear(v.position, targets[index])) {
                    return Estimate(false, time.toFloat(), distance, stopped, reverse, commands,
                        failureReason = "native-direct-tiles-blocked", targetIndex = index)
                }
                if (followDirectNodes) {
                    directNodes = directMoveNodes(v.position, targets[index]) ?: return Estimate(
                        false, time.toFloat(), distance, stopped, reverse, commands,
                        failureReason = "native-direct-too-long", targetIndex = index)
                    directNodeIndex = 0
                }
            }
            val route = if (active) targets else nativePrefix
            var cursor = if (active) index else prefixIndex
            if (!guided || !active) {
                if (motion.maxSpeed > 5f && cursor < route.lastIndex &&
                    v.position.distanceTo(route[cursor + 1]) < 19f) cursor++
                while (cursor < route.lastIndex && v.position.distanceTo(route[cursor]) < 16f) cursor++
            }
            if (active) index = cursor else prefixIndex = cursor
            if (active && cursor == targets.lastIndex && v.position.distanceTo(targets.last()) < MOVE_COMPLETION_RADIUS) {
                return Estimate(true, time.toFloat(), distance, stopped, reverse, commands)
            }
            if (guided && active && cursor < route.lastIndex && v.position.distanceTo(route[cursor]) < MOVE_COMPLETION_RADIUS) {
                intermediateReached = true
            }
            // Runtime evaluates every 80 ms and limits commands to at least 120 ms apart.
            if (guided && active && pendingIndex < 0 && (intermediateReached || time % 5 == 0) && time - lastCommandTime >= 8) {
                val next = chooseTarget(grid, v, targets, index, commandDelay, terrainClearance, intermediateReached)
                if (next > index) {
                    pendingIndex = next
                    pendingAt = time + max(1, ceil(commandDelay).toInt())
                    lastCommandTime = time
                    commands++
                } else if (intermediateReached) {
                    return Estimate(false, time.toFloat(), distance, stopped, reverse, commands,
                        failureReason = "no-safe-continuation", targetIndex = index)
                }
            }
            val before = v.position
            val nativeLeg = guided && active && followDirectNodes
            if (nativeLeg) {
                if (motion.maxSpeed > 5f && directNodeIndex < directNodes.lastIndex &&
                    v.position.distanceTo(directNodes[directNodeIndex + 1]) < 19f) directNodeIndex++
                while (directNodeIndex < directNodes.lastIndex && v.position.distanceTo(directNodes[directNodeIndex]) < 16f) {
                    directNodeIndex++
                }
            }
            if (intermediateReached) v.brake()
            else if (nativeLeg) v.step(directNodes[directNodeIndex], terminal = directNodeIndex == directNodes.lastIndex,
                remainingNodes = directNodes.size - directNodeIndex)
            else v.step(route[cursor], terminal = guided && active || cursor == route.lastIndex,
                remainingNodes = if (guided && active) directPathNodeCount(v.position.distanceTo(route[cursor])) else route.size - cursor)
            // A native path may legally run within one body radius of a blocked tile's edge.
            // Its center-based collision rule differs from our conservative candidate clearance.
            val clearance = if (!guided || !active) 0f else terrainClearance
            if (!grid.isClear(before, v.position, clearance)) return Estimate(false, time.toFloat(), distance, stopped, reverse, commands,
                failureReason = "collision", targetIndex = cursor, collisionFrom = before, collisionTo = v.position)
            val moved = before.distanceTo(v.position)
            distance += moved
            if (abs(v.speed) < 0.03f) stopped++
            if (v.speed < 0f) reverse += moved
        }
        return Estimate(false, MAX_SIMULATION_TIME.toFloat(), distance, stopped, reverse, commands,
            failureReason = "timeout", targetIndex = if (active) index else prefixIndex)
    }

    private fun chooseTarget(
        grid: MoveNavigationGrid, vehicle: Vehicle, targets: List<MovePoint>, index: Int,
        delay: Float, clearance: Float, intermediateReached: Boolean,
    ): Int {
        if (index >= targets.lastIndex) return index
        for (next in targets.lastIndex downTo index + 1) {
            val target = targets[next]
            val lead = max(20f, vehicle.body.maxSpeed * (delay + min(turnTime(vehicle, target), 45f) + 3f) + vehicle.body.radius)
            if (!intermediateReached && next != targets.lastIndex && vehicle.position.distanceTo(targets[index]) > lead) continue
            if (canTurnTowards(grid, vehicle, target, targets[index].takeUnless { intermediateReached },
                    ceil(delay).toInt(), clearance)) return next
        }
        return index
    }

    private fun turnTime(vehicle: Vehicle, target: MovePoint): Float =
        abs(angleDifference(vehicle.heading, angle(vehicle.position, target))) / vehicle.body.maxTurnSpeed +
            if (vehicle.body.turnAcceleration > 0f) vehicle.body.maxTurnSpeed / vehicle.body.turnAcceleration else 0f

    private fun canTurnTowards(
        grid: MoveNavigationGrid, vehicle: Vehicle, target: MovePoint, previousTarget: MovePoint?,
        wait: Int, clearance: Float,
    ): Boolean {
        if (!grid.isClear(vehicle.position, target, clearance)) return false
        val probe = vehicle.copy()
        val horizon = wait + (ceil(turnTime(vehicle, target)).toInt() + 8).coerceIn(8, 100)
        for (tick in 0 until horizon) {
            val before = probe.position
            if (tick == wait && (!grid.nativeDirectTilesClear(before, target) ||
                    (wait > 0 && !grid.isClear(before, target, clearance)))) return false
            val currentTarget = if (tick < wait) previousTarget else target
            if (currentTarget == null) probe.brake()
            else probe.step(currentTarget, terminal = true,
                remainingNodes = directPathNodeCount(probe.position.distanceTo(currentTarget)))
            if (!grid.isClear(before, probe.position, clearance)) return false
            if (tick >= wait && probe.position.distanceTo(target) < MOVE_COMPLETION_RADIUS) break
        }
        return true
    }

    private data class SearchEntry(val state: Int, val cost: Float, val priority: Float)

    /** State includes the incoming edge; reaching a corner from another heading is a different state. */
    private fun search(
        grid: MoveNavigationGrid, motion: MoveMotion, nodes: List<MovePoint>, edges: FloatArray,
        turnWeight: Float, clearance: Float, maxTargets: Int = MAX_ASSISTED_MOVE_TARGETS,
    ): List<MovePoint>? {
        val count = nodes.size
        val costs = FloatArray(count * count) { Float.POSITIVE_INFINITY }
        val parents = IntArray(costs.size) { -1 }
        val depths = IntArray(costs.size)
        val queue = PriorityQueue<SearchEntry>(compareBy<SearchEntry> { it.priority }.thenBy { it.state })
        costs[0] = 0f
        queue.add(SearchEntry(0, 0f, 0f))
        var expanded = 0
        while (queue.isNotEmpty() && expanded++ < 12_000) {
            val entry = queue.remove()
            if (entry.cost != costs[entry.state]) continue
            val previous = entry.state / count
            val current = entry.state % count
            if (current == 1) {
                val route = ArrayList<MovePoint>()
                var state = entry.state
                while (state != 0 && state >= 0) { route.add(nodes[state % count]); state = parents[state] }
                return route.asReversed()
            }
            if (depths[entry.state] >= maxTargets) continue
            val incoming = if (entry.state == 0) motion.headingDegrees else angle(nodes[previous], nodes[current])
            for (next in 1 until count) {
                if (next == current || next == previous) continue
                val edgeIndex = current * count + next
                var length = edges[edgeIndex]
                if (length.isNaN()) {
                    length = if (grid.isClear(nodes[current], nodes[next], clearance) &&
                        grid.nativeDirectTilesClear(nodes[current], nodes[next])) nodes[current].distanceTo(nodes[next]) else -1f
                    edges[edgeIndex] = length
                }
                if (length <= 0f) continue
                val direction = angle(nodes[current], nodes[next])
                val turnCost = abs(angleDifference(incoming, direction)) / motion.maxTurnSpeed
                val cost = entry.cost + length / motion.maxSpeed + turnWeight * turnCost
                val state = current * count + next
                if (cost >= costs[state]) continue
                costs[state] = cost
                parents[state] = entry.state
                depths[state] = depths[entry.state] + 1
                queue.add(SearchEntry(state, cost, cost + nodes[next].distanceTo(nodes[1]) / motion.maxSpeed))
            }
        }
        return null
    }

    private class NativeRouteShortcuts(
        private val grid: MoveNavigationGrid,
        private val start: MovePoint,
        private val route: List<MovePoint>,
        private val clearance: Float,
    ) {
        private val edges = ByteArray((route.size + 1) * route.size)

        private fun visible(from: Int, to: Int): Boolean {
            val key = from * route.size + to
            if (edges[key] == 0.toByte()) {
                val point = if (from == route.size) start else route[from]
                edges[key] = if (grid.nativeDirectTilesClear(point, route[to]) && grid.isClear(point, route[to], clearance)) 1 else -1
            }
            return edges[key] > 0
        }

        fun simplify(alternateLeg: Int = -1, skip: Int = 0, maxTargets: Int = MAX_ASSISTED_MOVE_TARGETS): List<MovePoint>? {
            val result = ArrayList<MovePoint>()
            var from = route.size
            var cursor = 0
            while (cursor < route.size && result.size < maxTargets) {
                var remaining = if (result.size == alternateLeg) skip else 0
                val next = (route.lastIndex downTo cursor).firstOrNull { index ->
                    if (!visible(from, index)) return@firstOrNull false
                    if (remaining == 0) return@firstOrNull true
                    remaining--
                    false
                } ?: return null
                from = next
                result.add(route[next])
                cursor = next + 1
            }
            return result.takeIf { cursor == route.size }
        }
    }

    /** Restricted, calibrated approximation of the native non-strafing movement controller. */
    private class Vehicle(val body: MoveMotion) {
        var position = body.position
        var heading = body.headingDegrees
        var speed = body.speedFraction
        var turnRate = body.currentTurnRate

        fun copy(): Vehicle = Vehicle(body.copy(position = position, headingDegrees = heading, speedFraction = speed, currentTurnRate = turnRate))

        fun brake() {
            speed = approach(speed, 0f, if (speed < 0f) body.acceleration else body.deceleration)
            move()
        }

        fun step(target: MovePoint, terminal: Boolean, remainingNodes: Int = if (terminal) 1 else 2) {
            val distance = position.distanceTo(target)
            var error = angleDifference(heading, angle(position, target))
            val reverseAllowed = body.reverseSpeedFraction > 0.95f || (body.formationLeader && remainingNodes > 0 && when {
                body.reverseSpeedFraction.toDouble() > 0.87 -> remainingNodes <= 9 && distance < 500f
                body.reverseSpeedFraction.toDouble() > 0.7 -> remainingNodes <= 4 && distance < 200f
                body.reverseSpeedFraction.toDouble() > 0.4 -> remainingNodes <= 2 && distance < 100f
                else -> false
            })
            val reversing = reverseAllowed && abs(error) > 100f
            if (reversing) error = angleDifference(heading, angle(position, target) + 180f)
            if (abs(error) > 0.01f) {
                var rotation: Float
                if (body.turnAcceleration <= 0f) {
                    rotation = error.coerceIn(-body.maxTurnSpeed, body.maxTurnSpeed)
                } else {
                    val desired = sign(error) * if (abs(error) < abs(turnRate) / body.turnAcceleration) body.turnAcceleration else body.maxTurnSpeed
                    turnRate = approach(turnRate, desired, body.turnAcceleration)
                    rotation = turnRate
                    if (abs(rotation) > abs(error)) { rotation = error; turnRate = 0f }
                }
                heading = normalize(heading + rotation)
            }
            var gate = when { distance > 60f -> 89f; distance > 19f -> 46f; else -> 20f }
            if (body.maxTurnSpeed.toDouble() < 1.4) gate = if (distance > 80f) gate * 0.5f else 17f
            if (body.maxSpeed > 5f && abs(speed) < 0.01f) gate = 1f
            if (body.maxTurnSpeed.toDouble() < 1.1) gate *= 0.7f
            if (speed > 0.4f && distance > 130f) gate = 180f
            var desired = if (abs(error) <= gate && distance >= if (terminal) 4f else 0f) 1f else 0f
            if (terminal && desired != 0f) {
                if (distance < 18f && body.deceleration < 0.13f && body.maxSpeed > 1f) desired *= 0.5f
                if (distance < 13f && body.deceleration < 0.15f && body.maxSpeed > 0.9f) desired *= 0.5f
                if (body.maxSpeed > 5f) {
                    if (distance < 18f) desired = min(desired, 0.5f)
                    if (distance < 9f) desired = min(desired, 0.25f)
                }
            }
            if (distance < 3f) desired = 0f
            if (reversing) desired = -desired * body.reverseSpeedFraction
            speed = approach(speed, desired, if (speed < desired) body.acceleration else body.deceleration)
            move()
        }

        private fun move() {
            val radians = heading * (PI.toFloat() / 180f)
            position = MovePoint(position.x + cos(radians) * body.maxSpeed * speed, position.y + sin(radians) * body.maxSpeed * speed)
        }
    }

    private fun approach(value: Float, target: Float, delta: Float): Float =
        if (value < target) min(value + delta, target) else max(value - delta, target)

    private fun angle(from: MovePoint, to: MovePoint): Float = atan2(to.y - from.y, to.x - from.x) * (180f / PI.toFloat())
    private fun directPathNodeCount(distance: Float): Int = max(1, (distance / 20f).toInt())
    private fun normalize(angle: Float): Float = ((angle + 180f) % 360f + 360f) % 360f - 180f
    private fun angleDifference(from: Float, to: Float): Float = normalize(to - from)
}
