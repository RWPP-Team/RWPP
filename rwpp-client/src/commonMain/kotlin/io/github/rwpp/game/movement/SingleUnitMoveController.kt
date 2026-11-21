/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.logging.logger
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.hud.GameUiPauseState
import io.github.rwpp.mixin.building.NativeBuildingPlanningCommandAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.max

internal fun sameMoveTarget(a: MovePoint?, b: MovePoint?): Boolean = a != null && b != null &&
    abs(a.x - b.x) <= 0.25f && abs(a.y - b.y) <= 0.25f

internal fun isSupportedMoveInput(command: CommandControllerCommand): Boolean =
    command.waypoint?.type in listOf(WaypointType.move, WaypointType.build) && !command.isReplayCommand &&
        !command.finishNonBuildWaypoints && !command.removeFromQueue && !command.clearWaypointsFirst &&
        !command.hasSystemAction && command.attackMode == null && command.targetUnit == null &&
        (command.actionId == null || command.actionId == ActionId.EMPTY)

internal fun sameQueuedOrder(a: Waypoint, b: Waypoint): Boolean = a.matches(b) && a.type == b.type &&
    sameMoveTarget(MovePoint(a.targetX, a.targetY), MovePoint(b.targetX, b.targetY)) &&
    a.automatic == b.automatic && a.addedByAction == b.addedByAction &&
    a.maxWaypointDuration == b.maxWaypointDuration && a.repairReclaimRangePadding == b.repairReclaimRangePadding

/** Recognizes native consumption of a submitted batch, including a user's later Shift append. */
internal class MoveQueueProgress(val orders: List<Waypoint>, val protectedCount: Int) {
    init { require(protectedCount in 1..orders.size) }
    var cursor = 0
        private set

    /** Returns null for a changed queue; [orders.size] means the native queue has completed. */
    fun observe(actual: List<Waypoint>): Int? {
        if (actual.isEmpty()) { cursor = orders.size; return cursor }
        for (index in cursor..orders.lastIndex) {
            val remaining = orders.size - index
            if (actual.size >= remaining && (0 until remaining).all { sameQueuedOrder(orders[index + it], actual[it]) }) {
                cursor = index
                return index
            }
        }
        return null
    }
}

/**
 * Plans only the current user segment. A validated batch is submitted once through ordinary player
 * commands; native waypoint advancement owns every intermediate and all original following orders.
 */
internal object SingleUnitMoveController {
    private class Watch(val engine: GameEngine, val unit: OrderableUnit, val token: Long) {
        val map = engine.map
        val team = engine.playerTeam
        var head: Waypoint? = null
        var handled = false
        var goal: MovePoint? = null
        var startedAt = engine.gameTimeMillis
        var planningStartedAt: Int? = null
        var snapshot: List<Waypoint>? = null
        var nativeContinuation: NativeMoveContinuation? = null
        var job: Job? = null
        @Volatile var result: PlanningResult? = null
        @Volatile var generation = 0
        var route: MoveRoutePlan? = null
        var submitted: List<CommandControllerCommand> = emptyList()
        var submittedReceipts: List<MoveInputBarrier.Receipt> = emptyList()
        var inputReceipt: MoveInputBarrier.Receipt? = null
        var inputRegisteredAt = engine.gameTimeMillis
        var batch: MoveQueueProgress? = null
        var batchApplied = false
        var batchSentAt = 0
        var lastPosition = MovePoint(unit.x, unit.y)
        var lastSampleAt = engine.gameTimeMillis
        var lastTraceAt = engine.gameTimeMillis
        var lastEvaluation = Int.MIN_VALUE
        var distance = 0f
        var stoppedMillis = 0
        var reverseDistance = 0f
    }
    private data class PlanningInput(
        val generation: Int, val createdAt: Int, val grid: MoveNavigationGrid,
        val motion: MoveMotion, val goal: MovePoint, val nativeRoute: List<MovePoint>,
        val commandDelay: Float, val orders: List<Waypoint>, val retainHead: Boolean,
        val buildApproach: MoveBuildApproach?,
    )
    private data class PlanningResult(val input: PlanningInput, val route: MoveRoutePlan?, val reason: String?)

    private val watches = ArrayList<Watch>()
    private val registrations = LinkedHashSet<CommandControllerCommand>()
    private val inputBarriers = HashMap<OrderableUnit, MoveInputBarrier>()
    private var barrierEngine: GameEngine? = null
    private var barrierMap: Any? = null
    private var issuingAutomatic = false
    private var nextToken = 0L
    private val planningMutex = Mutex()

    /** addUnit can precede setBuildWaypoint. Record identity now; inspect the complete command at UI tail. */
    fun afterLocalUnitAdded(target: Any) {
        if (issuingAutomatic) return
        if (watches.isEmpty() && inputBarriers.isEmpty() &&
            settings()?.let { it.singleUnitMoveAssist || it.singleUnitMoveDebug } != true) return
        val command = target as? CommandControllerCommand ?: return
        val engine = GameEngine.getInstance() ?: return
        synchronized(engine.gameUpdateLock) {
            resetBarriersForWorld(engine)
            if (!isLocalOutgoing(engine, command)) return
            if (registrations.size >= MAX_REGISTRATIONS) registrations.remove(registrations.first())
            registrations.add(command)
        }
    }

    fun afterLocalUnitsAdded(target: Any, value: Any) {
        if (GameEngine.getInstance()?.interfaceEngine === target) afterLocalUnitAdded(value)
    }

    /** A sent packet is not an applied order; only native execution can acknowledge an input. */
    fun afterNativeCommandApplied(target: Any) {
        if (registrations.isEmpty() && inputBarriers.isEmpty()) return
        val command = target as? CommandControllerCommand ?: return
        val engine = GameEngine.getInstance() ?: return
        synchronized(engine.gameUpdateLock) {
            resetBarriersForWorld(engine)
            if (engine.replayEngine.isPlayingReplay() || command.isReplayCommand ||
                command.team !== engine.playerTeam ||
                (command.player != null && command.player !== engine.playerTeam)) return
            // Single-player execution can precede the next UI tail. By now BUILD configuration is complete.
            processRegistrations(engine)
            if (!changesMovement(command)) return
            val units = (command as? NativeBuildingPlanningCommandAccess)?.rwppPlanningCommandUnits()
                ?.filterIsInstance<OrderableUnit>().orEmpty()
            for (unit in units) {
                val barrier = inputBarriers[unit] ?: continue
                val appliedOrder = if (command.queueOrder) unit.lastWaypoint else unit.currentWaypoint
                barrier.acknowledge(command, appliedOrder)
                if (barrier.empty && !barrier.poisoned) inputBarriers.remove(unit)
            }
        }
    }

    fun beforeApplyPath(target: Any, value: Any) {
        val unit = target as? OrderableUnit ?: return
        val engine = value as? GameEngine ?: return
        val current = watches.firstOrNull { it.unit === unit && it.engine === engine } ?: return
        val order = unit.currentWaypoint ?: return
        if (current.head !== order) {
            current.batch?.let { batch ->
                if (current.submittedReceipts.any { !it.applied }) return
                val index = NativeMoveQueue.snapshot(unit)?.let(batch::observe) ?: return
                if (index < batch.protectedCount) return
                current.batch = null
                current.submitted = emptyList()
                current.submittedReceipts = emptyList()
            }
            beginSegment(current, order, engine.gameTimeMillis)
        }
        if (current.handled) return
        if (order.type !in listOf(WaypointType.move, WaypointType.build)) return
        current.nativeContinuation = NativeMoveTerrain.pendingContinuation(engine, unit, MovePoint(order.targetX, order.targetY))
    }

    fun update(target: Any) {
        val engine = GameEngine.getInstance() ?: return
        if (engine.interfaceEngine !== target) return
        synchronized(engine.gameUpdateLock) {
            resetBarriersForWorld(engine)
            val settings = settings()
            processRegistrations(engine)
            if (settings == null || (!settings.singleUnitMoveAssist && !settings.singleUnitMoveDebug) ||
                !engine.gameLoopActive || engine.map?.mapLoaded != true || engine.replayEngine.isPlayingReplay()) {
                watches.toList().forEach { stop(it, "disabled-or-world-changed", discardUnsent = false) }
                registrations.clear()
                return
            }
            if (engine.gamePaused || engine.networkEngine.shouldGameBePaused() || GameUiPauseState.blocked ||
                engine.interfaceEngine.pauseMenuOpen) return
            watches.toList().forEach { watch ->
                runCatching { updateLocked(watch, settings) }.onFailure { failure ->
                    cancelPlanning(watch)
                    watch.handled = true
                    debug("native-fallback", watch)
                    if (settings.singleUnitMoveDebug) logger().warn("Move assist queue update failed", failure)
                }
            }
        }
    }

    private fun processRegistrations(engine: GameEngine) {
        val inputs = registrations.toList()
        registrations.clear()
        for (command in inputs) {
            // An already applied local input is still useful for tracking, but a foreign/replay order is not.
            if (command.isReplayCommand || command.team !== engine.playerTeam ||
                (command.player != null && command.player !== engine.playerTeam)) continue
            val units = (command as? NativeBuildingPlanningCommandAccess)?.rwppPlanningCommandUnits()
                ?.filterIsInstance<OrderableUnit>().orEmpty()
            if (!changesMovement(command)) continue
            val supported = units.size == 1 && isSupportedMoveInput(command)
            for (unit in units) {
                val receipt = inputBarriers.getOrPut(unit, ::MoveInputBarrier).register(command)
                var current = watches.firstOrNull { it.engine === engine && it.unit === unit }
                if (!command.queueOrder || !supported) {
                    current?.let { stop(it, "manual-command", discardUnsent = true) }
                    current = null
                } else current?.let { cancelPlanning(it) }
                val enabled = settings()?.let { it.singleUnitMoveAssist || it.singleUnitMoveDebug } == true
                if (enabled && supported && owns(engine, unit) && !unit.isBuilding() && current == null) {
                    if (watches.size >= MAX_TRACKED_UNITS) stop(watches.first(), "observation-capacity", false)
                    current = Watch(engine, unit, ++nextToken).also { watches.add(it) }
                }
                if (supported) current?.let {
                    it.inputReceipt = receipt
                    it.inputRegisteredAt = engine.gameTimeMillis
                }
            }
        }
    }

    private fun updateLocked(current: Watch, settings: Settings) {
        val engine = current.engine
        val unit = current.unit
        if (!sameWorld(current) || !owns(engine, unit)) { stop(current, "world-or-unit-changed", false); return }
        val motion = NativeMoveTerrain.motion(unit) ?: run { stop(current, "unsupported-motion", false); return }
        val now = engine.gameTimeMillis
        recordSample(current, motion, now, settings.singleUnitMoveDebug)
        if (inputBarriers[unit]?.poisoned == true) {
            stop(current, "native-input-order-unconfirmed", false); return
        }
        current.inputReceipt?.let { receipt ->
            if (!receipt.received) {
                if (now - current.inputRegisteredAt > NATIVE_PATH_WAIT_MILLIS) {
                    stop(current, "input-application-unobserved", false)
                }
                return
            }
            if (!receipt.applied) { stop(current, "native-input-rejected", false); return }
            current.inputReceipt = null
        }
        // All external local orders get to settle first. In particular, never overwrite an in-flight Shift/BUILD.
        if (pendingCommands(engine).any { it !in current.submitted && addresses(it, unit) && changesMovement(it) }) {
            cancelPlanning(current)
            return
        }
        val head = unit.currentWaypoint
        val actual = NativeMoveQueue.snapshot(unit)
        val batch = current.batch
        if (batch != null) {
            if (pendingCommands(engine).any { pending -> current.submitted.any { it === pending } }) return
            if (!current.batchApplied) {
                if (current.submittedReceipts.any { !it.received }) {
                    if (now - current.batchSentAt <= NATIVE_PATH_WAIT_MILLIS) return
                    current.batch = null
                    current.handled = true
                    debug("queue-application-unobserved", current)
                    return
                }
                if (current.submittedReceipts.any { !it.applied }) {
                    stop(current, "native-batch-rejected", false); return
                }
                current.batchApplied = true
                debug("queue-applied", current)
            }
            val oldCursor = batch.cursor
            val index = actual?.let(batch::observe)
            if (index != null && index < batch.protectedCount) {
                if (index != oldCursor) debug("native-queue-advance", current)
                return
            }
            debug(if (index != null) "segment-completed" else "native-queue-changed", current, "order-end")
            current.batch = null
            current.submitted = emptyList()
            current.submittedReceipts = emptyList()
            current.head = null
        }
        if (head == null) { stop(current, "arrived", false); return }
        if (current.head !== head) beginSegment(current, head, now)
        if (current.handled || !settings.singleUnitMoveAssist) return
        if (head.type !in listOf(WaypointType.move, WaypointType.build)) { current.handled = true; return }
        if (actual == null || actual.isEmpty()) { current.handled = true; debug("unsupported-native-queue", current); return }
        if (head.type == WaypointType.build && insideBuildRange(unit, head, motion.position)) {
            current.handled = true
            debug("native-building-in-range", current)
            return
        }
        if (current.lastEvaluation != Int.MIN_VALUE && now - current.lastEvaluation < EVALUATION_INTERVAL_MILLIS) return
        current.lastEvaluation = now
        val requestedGoal = MovePoint(head.targetX, head.targetY)
        if (current.planningStartedAt == null) {
            val nativeRoute = NativeMoveTerrain.nativeRoute(unit, requestedGoal, current.nativeContinuation)
            if (nativeRoute == null) {
                if (now - current.startedAt > NATIVE_PATH_WAIT_MILLIS) {
                    current.handled = true
                    debug("no-complete-native-path", current)
                }
                return
            }
            val goal = nativeRoute.last()
            val grid = NativeMoveTerrain.capture(engine, unit, goal, nativeRoute) ?: run {
                current.handled = true; debug("terrain-unavailable", current); return
            }
            val retainHead = head.type == WaypointType.build
            val available = NativeMoveQueue.CAPACITY - actual.size + if (retainHead) 0 else 1
            if (available < 2) { current.handled = true; debug("native-queue-capacity", current); return }
            current.snapshot = actual
            current.planningStartedAt = now
            current.goal = goal
            startPlanning(current, PlanningInput(current.generation, now, grid, motion, goal,
                nativeRoute, commandDelay(engine), actual, retainHead,
                if (retainHead) MoveBuildApproach(requestedGoal, unit.getBuildRange(head.unitType)) else null),
                available.coerceAtMost(MAX_ASSISTED_MOVE_TARGETS))
            return
        }
        val result = current.result
        if (result == null) {
            if (now - (current.planningStartedAt ?: now) > PLANNING_MAX_AGE_MILLIS) {
                cancelPlanning(current); current.handled = true; debug("planning-expired", current)
            }
            return
        }
        current.result = null
        val input = result.input
        val route = result.route
        if (input.generation != current.generation || !NativeMoveQueue.sameQueue(unit, input.orders) ||
            now - input.createdAt > PLANNING_MAX_AGE_MILLIS ||
            motion.position.distanceTo(input.motion.position) > max(24f, motion.maxSpeed * 12f) ||
            abs(((motion.headingDegrees - input.motion.headingDegrees + 540f) % 360f + 360f) % 360f - 180f) > 60f) {
            cancelPlanning(current)
            debug("planning-state-refreshed", current)
            return
        }
        current.handled = true
        if (route == null) { debug(result.reason ?: "no-time-benefit", current); return }
        if (route.targets.size !in 2..MAX_ASSISTED_MOVE_TARGETS || !sameMoveTarget(route.targets.last(), input.goal) ||
            route.targets.any { !it.finite } || !route.plannedEstimatedTime.isFinite() ||
            !route.nativeEstimatedTime.isFinite() || route.plannedEstimatedTime >= route.nativeEstimatedTime ||
            !route.terrainClearance.isFinite() || route.terrainClearance < 0f) {
            debug("invalid-candidate", current); return
        }
        if (input.retainHead && insideBuildRange(unit, head, motion.position)) {
            debug("native-building-in-range", current); return
        }
        val fresh = NativeMoveTerrain.capture(engine, unit, input.goal, input.nativeRoute) ?: return
        var from = motion.position
        for (target in route.targets) {
            if (!fresh.isClear(from, target, route.terrainClearance) || !fresh.nativeDirectTilesClear(from, target)) {
                debug("terrain-changed", current); return
            }
            from = target
        }
        // No per-waypoint commands or acknowledgements after this one transaction.
        issuingAutomatic = true
        val commands = try { NativeMoveQueue.submit(engine, unit, input.orders, route.targets, input.retainHead) }
            finally { issuingAutomatic = false }
        if (commands == null) { debug("batch-not-submitted", current); return }
        current.route = route
        current.submitted = commands
        val barrier = inputBarriers.getOrPut(unit, ::MoveInputBarrier)
        current.submittedReceipts = commands.map(barrier::register)
        val orders = commands.map { Waypoint().apply { copyFrom(it.waypoint) } }
        current.batch = MoveQueueProgress(orders, route.targets.size + if (input.retainHead) 1 else 0)
        current.batchApplied = false
        current.batchSentAt = now
        debug("queue-submitted", current)
    }

    private fun beginSegment(current: Watch, head: Waypoint, now: Int) {
        cancelPlanning(current)
        current.nativeContinuation = null
        current.head = head
        current.handled = false
        current.goal = MovePoint(head.targetX, head.targetY)
        current.startedAt = now
        current.route = null
        current.lastEvaluation = Int.MIN_VALUE
        current.lastPosition = MovePoint(current.unit.x, current.unit.y)
        current.lastSampleAt = now
        current.distance = 0f
        current.stoppedMillis = 0
        current.reverseDistance = 0f
        debug("observe-native", current, "order-start")
    }

    private fun startPlanning(current: Watch, input: PlanningInput, maxTargets: Int) {
        val debugEnabled = settings()?.singleUnitMoveDebug == true
        current.job = GameScope.launch(Dispatchers.Default) {
            planningMutex.withLock {
                ensureActive()
                var reason: String? = null
                val route = try {
                    MoveRoutePlanner.planQueued(input.grid, input.motion, input.goal, input.nativeRoute,
                        input.commandDelay, maxTargets, input.buildApproach) { diagnostic ->
                        if (diagnostic.stage == "result" && diagnostic.reason != "planned") reason = diagnostic.reason
                        if (debugEnabled) logger().info("Move assist planning: mode=queued token={} unit={} diagnostic={}",
                            current.token, current.unit.id, diagnostic)
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    reason = "planning-exception"
                    if (debugEnabled) logger().warn("Move assist queued planning failed", failure)
                    null
                }
                ensureActive()
                if (current.generation == input.generation) current.result = PlanningResult(input, route, reason)
            }
        }
    }

    private fun cancelPlanning(current: Watch) {
        current.generation++
        current.job?.cancel()
        current.job = null
        current.result = null
        current.planningStartedAt = null
        current.snapshot = null
    }

    private fun stop(current: Watch, reason: String, discardUnsent: Boolean) {
        if (!watches.remove(current)) return
        cancelPlanning(current)
        if (discardUnsent) {
            val commands = current.engine.commandController
            current.submitted.forEachIndexed { index, command ->
                val removed = if (current.engine.networkEngine.networked) {
                    // localPendingCommands can contain an already broadcast server command.
                    val pendingRemoved = commands.networkPendingCommands.remove(command)
                    commands.issuedCommands.remove(command) || pendingRemoved
                } else commands.localPendingCommands.remove(command)
                if (removed) current.submittedReceipts.getOrNull(index)?.let {
                    inputBarriers[current.unit]?.discard(it)
                }
            }
        }
        debug(reason, current, "order-end")
    }

    private fun insideBuildRange(unit: OrderableUnit, order: Waypoint, position: MovePoint): Boolean {
        if (order.type != WaypointType.build) return false
        val type = order.unitType ?: return true
        val range = unit.getBuildRange(type)
        return range.isFinite() && position.distanceTo(MovePoint(order.targetX, order.targetY)) <= range
    }

    private fun changesMovement(command: CommandControllerCommand): Boolean = command.waypoint != null ||
        command.clearWaypointsFirst || command.finishNonBuildWaypoints || command.removeFromQueue

    private fun isLocalOutgoing(engine: GameEngine, command: CommandControllerCommand): Boolean =
        !command.isReplayCommand && command.team === engine.playerTeam &&
            (command.player == null || command.player === engine.playerTeam) && pendingCommands(engine).any { it === command }

    private fun pendingCommands(engine: GameEngine): List<CommandControllerCommand> {
        val commands = engine.commandController
        val pending = if (engine.networkEngine.networked) commands.networkPendingCommands.toArray().asList() +
            commands.issuedCommands.toArray().asList() else commands.localPendingCommands.toArray().asList()
        return pending.filterIsInstance<CommandControllerCommand>()
    }

    private fun addresses(command: CommandControllerCommand, unit: OrderableUnit): Boolean =
        !command.isReplayCommand && command.team === unit.team &&
            (command as? NativeBuildingPlanningCommandAccess)?.rwppPlanningCommandUnits()?.any { it === unit } == true

    private fun commandDelay(engine: GameEngine): Float = if (engine.networkEngine.networked)
        max(1f, (engine.networkEngine.nextBlockingFrame - engine.simulationTick).coerceIn(0, 60).toFloat() + 2f) else 1f

    private fun settings(): Settings? = runCatching { appKoin.get<Settings>() }.getOrNull()
    private fun owns(engine: GameEngine, unit: OrderableUnit): Boolean = !unit.dead && !unit.removed &&
        unit.transportedBy == null && unit.team === engine.playerTeam && !engine.playerTeam.isSpectator() &&
        engine.interfaceEngine.canPlayerControlUnit(unit)
    private fun sameWorld(current: Watch): Boolean = GameEngine.getInstance() === current.engine &&
        current.engine.map === current.map && current.engine.playerTeam === current.team

    private fun resetBarriersForWorld(engine: GameEngine) {
        if (barrierEngine !== engine || barrierMap !== engine.map) {
            registrations.clear()
            inputBarriers.clear()
            barrierEngine = engine
            barrierMap = engine.map
        }
    }

    private fun recordSample(current: Watch, motion: MoveMotion, now: Int, enabled: Boolean) {
        val elapsed = (now - current.lastSampleAt).coerceAtLeast(0)
        val moved = current.lastPosition.distanceTo(motion.position)
        current.distance += moved
        if (abs(motion.speedFraction * motion.maxSpeed) < 0.03f) current.stoppedMillis += elapsed
        if (motion.speedFraction < 0f) current.reverseDistance += moved
        current.lastPosition = motion.position
        current.lastSampleAt = now
        if (!enabled || now - current.lastTraceAt < 250) return
        current.lastTraceAt = now
        logger().info("Move assist sample: mode=queued token={} unit={} elapsedMs={} position={} heading={} throttle={} queueSize={} target={} cursor={}",
            current.token, current.unit.id, now - current.startedAt, motion.position, motion.headingDegrees,
            motion.speedFraction, current.unit.waypointCount, current.unit.currentWaypoint?.let { MovePoint(it.targetX, it.targetY) }, current.batch?.cursor)
    }

    private fun debug(reason: String, current: Watch, event: String = "control") {
        if (settings()?.singleUnitMoveDebug != true) return
        logger().info("Move assist: {} mode=queued unit={} unitClass={} token={} event={} goal={} elapsedMs={} distance={} stoppedMs={} reverseDistance={} commands={} queueSize={} nativeSteps={} plannedSteps={} targets={}",
            reason, current.unit.id, current.unit.javaClass.name, current.token, event, current.goal,
            current.engine.gameTimeMillis - current.startedAt, current.distance, current.stoppedMillis, current.reverseDistance,
            current.submitted.size, current.unit.waypointCount, current.route?.nativeEstimatedTime,
            current.route?.plannedEstimatedTime, current.route?.targets)
    }

    private const val EVALUATION_INTERVAL_MILLIS = 80
    private const val NATIVE_PATH_WAIT_MILLIS = 5_000
    private const val PLANNING_MAX_AGE_MILLIS = 1_500
    private const val MAX_TRACKED_UNITS = 16
    private const val MAX_REGISTRATIONS = 128
}
