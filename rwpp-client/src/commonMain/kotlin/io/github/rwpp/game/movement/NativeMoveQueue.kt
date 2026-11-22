/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.mixin.hud.NativeInterfaceAccess
import kotlin.math.abs

/** Replaces one native queue prefix through ordinary outgoing commands, under the game update lock. */
internal object NativeMoveQueue {
    const val CAPACITY = 29

    /** Returns independent copies only when native command reissue preserves every queued order. */
    fun snapshot(unit: OrderableUnit): List<Waypoint>? = runCatching {
        val count = unit.waypointCount
        if (count !in 1..CAPACITY) return null
        (0 until count).map { index ->
            val order = unit.getWaypoint(index) ?: return null
            copyOrder(order) ?: return null
        }
    }.getOrNull()

    /** Includes private serialized properties, rather than Waypoint.matches' approximate position test. */
    fun sameQueue(unit: OrderableUnit, expected: List<Waypoint>): Boolean = runCatching {
        expected.size in 1..CAPACITY && unit.waypointCount == expected.size &&
            expected.indices.all { index ->
                val order = unit.getWaypoint(index)
                order != null && canReissue(order) && canReissue(expected[index]) &&
                    bytes(order).contentEquals(bytes(expected[index]))
            }
    }.getOrDefault(false)

    /**
     * The caller suppresses its own local input hooks around this operation. The whole batch is
     * registered before native networking can consume it; no live unit waypoint is changed here.
     */
    fun submit(
        engine: GameEngine,
        unit: OrderableUnit,
        expected: List<Waypoint>,
        targets: List<MovePoint>,
        retainHead: Boolean,
    ): List<CommandControllerCommand>? = submitUsing(engine, unit, expected, targets, retainHead) {
        (engine.interfaceEngine as Any as NativeInterfaceAccess).rwppCreatePlanningCommand()
    }

    /** The factory seam exercises registration failures without replacing native command objects. */
    internal fun submitUsing(
        engine: GameEngine,
        unit: OrderableUnit,
        expected: List<Waypoint>,
        targets: List<MovePoint>,
        retainHead: Boolean,
        createCommand: () -> CommandControllerCommand,
    ): List<CommandControllerCommand>? = synchronized(engine.gameUpdateLock) {
        if (GameEngine.getInstance() !== engine || unit.team !== engine.playerTeam ||
            unit.dead || unit.removed || engine.replayEngine.isPlayingReplay() || !sameQueue(unit, expected)
        ) return@synchronized null
        val buildRange = if (retainHead) {
            runCatching { expected.first().constructionRange(unit) }.getOrNull() ?: return@synchronized null
        } else null
        val orders = prepare(expected, targets, retainHead, buildRange) ?: return@synchronized null
        val controller = engine.commandController
        val precedingLocal = controller.localPendingCommands.toArray().toSet()
        val precedingNetwork = controller.networkPendingCommands.toArray().toSet()
        try {
            orders.mapIndexed { index, order ->
                createCommand().apply {
                    queueOrder = index != 0
                    cancelDuplicateWaypoint = false
                    waypoint = copyOrder(order) ?: error("Unreissuable queued order")
                    source = "RWPP queued move assistance"
                    // Native networking can let later queued orders overtake a replacement while
                    // its optional input-side prefetched path is pending. Empty prefetch lists are
                    // ready immediately; native execution still requests each leg's ordinary path.
                    pathfindingQueued = true
                    addUnit(unit)
                }
            }
        } catch (_: Throwable) {
            // getPlayerCommand registers immediately, including before a later configuration fails.
            // Only outgoing commands created inside this locked transaction are ours to remove.
            controller.localPendingCommands.toArray().filterNot { it in precedingLocal }
                .forEach { controller.localPendingCommands.remove(it) }
            controller.networkPendingCommands.toArray().filterNot { it in precedingNetwork }
                .forEach { controller.networkPendingCommands.remove(it) }
            null
        }
    }

    /** An immutable queue transformation shared by the native adapter and its regression tests. */
    internal fun prepare(
        expected: List<Waypoint>,
        targets: List<MovePoint>,
        retainHead: Boolean,
        buildRange: Float? = null,
    ): List<Waypoint>? = runCatching {
        if (expected.size !in 1..CAPACITY || targets.size !in 1..MAX_ASSISTED_MOVE_TARGETS ||
            expected.any { !canReissue(it) } || targets.any { !it.finite }
        ) return null
        val head = expected.first()
        val destination = MovePoint(head.targetX, head.targetY)
        if (retainHead) {
            if ((head.type != WaypointType.build && head.unfinishedConstructionTarget() == null) ||
                buildRange == null || !buildRange.isFinite() || buildRange <= 0f ||
                targets.last().distanceTo(destination) > buildRange
            ) return null
        } else if (head.type != WaypointType.move ||
            abs(targets.last().x - destination.x) > 0.01f || abs(targets.last().y - destination.y) > 0.01f
        ) return null
        val retained = expected.size - if (retainHead) 0 else 1
        if (targets.size + retained > CAPACITY) return null
        val result = targets.mapTo(ArrayList()) { target ->
            Waypoint().apply { setMoveTo(target.x, target.y) }
        }
        if (!retainHead) result[result.lastIndex] = copyOrder(head) ?: return null
        val suffix = if (retainHead) expected else expected.drop(1)
        for (order in suffix) result += copyOrder(order) ?: return null
        result
    }.getOrNull()

    private fun copyOrder(order: Waypoint): Waypoint? {
        if (!canReissue(order)) return null
        val copy = Waypoint().apply { copyFrom(order) }
        // copyFrom intentionally excludes script timing/range flags and unresolved unit IDs.
        // Refuse any extra state it would discard, even if that state is not publicly accessible.
        return copy.takeIf { bytes(order).contentEquals(bytes(it)) }
    }

    private fun canReissue(order: Waypoint): Boolean =
        order.supportsMoveAssist() && order.targetX.isFinite() && order.targetY.isFinite() &&
            !order.automatic && !order.addedByAction &&
            order.repairReclaimRangePadding == -1f && order.maxWaypointDuration == -1f &&
            (if (order.type == WaypointType.build) order.unitType != null else order.unitType == null)

    private fun bytes(order: Waypoint): ByteArray = OutputNetStream().also(order::write).toByteArray()
}
