/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.MovementType
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.game.units.af as NativePathNode
import com.corrodinggames.rts.game.units.Unit as NativeUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.path.Path
import com.corrodinggames.rts.gameFramework.path.PathSolverNode
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class NativeMoveContinuation(
    val goal: MovePoint,
    val appliedPrefix: List<MovePoint>,
    val tail: List<MovePoint>,
)

/** Read-only snapshots: native cost rebuilding and unit motion remain entirely native. */
internal object NativeMoveTerrain {
    fun capture(
        engine: GameEngine,
        unit: OrderableUnit,
        goal: MovePoint,
        nativeRoute: List<MovePoint>,
    ): MoveNavigationGrid? = runCatching {
        synchronized(engine.gameUpdateLock) {
            if (GameEngine.getInstance() !== engine || !engine.gameLoopActive) return@synchronized null
            val map = engine.map ?: return@synchronized null
            val viewer = engine.playerTeam ?: return@synchronized null
            if (!map.mapLoaded || !goal.finite || nativeRoute.isEmpty() || nativeRoute.size > MAX_BASELINE_NODES ||
                nativeRoute.any { !it.finite } || !unit.x.isFinite() || !unit.y.isFinite() ||
                unit.movementType != MovementType.LAND || !unit.radius.isFinite() || unit.radius <= 0f
            ) return@synchronized null
            if (map.fogOfWarEnabled && viewer.fogGrid == null) return@synchronized null
            val tileWidth = map.tileWidth
            val tileHeight = map.tileHeight
            val mapWidth = map.mapWidthInTiles
            val mapHeight = map.mapHeightInTiles
            if (tileWidth <= 0 || tileHeight <= 0 || mapWidth <= 0 || mapHeight <= 0) return@synchronized null
            val pixelWidth = mapWidth.toDouble() * tileWidth
            val pixelHeight = mapHeight.toDouble() * tileHeight
            fun onMap(point: MovePoint): Boolean = point.finite && point.x >= 0f && point.y >= 0f &&
                    point.x < pixelWidth && point.y < pixelHeight
            val start = MovePoint(unit.x, unit.y)
            if (!onMap(start) || !onMap(goal) || nativeRoute.any { !onMap(it) }) return@synchronized null

            // getCosts is a lookup. Do not call refreshMapCosts/isLineClear: those rebuild native grids.
            val costs = engine.pathEngine?.getCosts(MovementType.LAND) ?: return@synchronized null
            val terrain = costs.terrainCost ?: return@synchronized null
            if (costs.width != mapWidth || costs.height != mapHeight ||
                mapWidth.toLong() * mapHeight > terrain.size.toLong()
            ) return@synchronized null
            var minX = min(start.x, goal.x)
            var minY = min(start.y, goal.y)
            var maxX = max(start.x, goal.x)
            var maxY = max(start.y, goal.y)
            nativeRoute.forEach {
                minX = min(minX, it.x)
                minY = min(minY, it.y)
                maxX = max(maxX, it.x)
                maxY = max(maxY, it.y)
            }
            val paddingX = max(tileWidth * ROI_PADDING_TILES, unit.radius + tileWidth * 2f)
            val paddingY = max(tileHeight * ROI_PADDING_TILES, unit.radius + tileHeight * 2f)
            val left = floor((minX - paddingX) / tileWidth).toInt().coerceAtLeast(0)
            val top = floor((minY - paddingY) / tileHeight).toInt().coerceAtLeast(0)
            val right = floor((maxX + paddingX) / tileWidth).toInt().coerceAtMost(mapWidth - 1)
            val bottom = floor((maxY + paddingY) / tileHeight).toInt().coerceAtMost(mapHeight - 1)
            val width = right - left + 1
            val height = bottom - top + 1
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_CAPTURE_CELLS) {
                return@synchronized null
            }
            val blocked = BooleanArray(width * height)
            for (x in left..right) for (y in top..bottom) {
                blocked[(x - left) * height + y - top] = terrain[x * mapHeight + y].toInt() == -1
            }

            // unitBlocked/clearance include unseen buildings; unitInfluence includes hidden units.
            // Raster only observable buildings, using the same origin and inclusive footprint as native.
            val list = NativeUnit.unitList
            val units = list.items()
            for (index in 0 until min(list.size, units.size)) {
                val building = units[index] ?: continue
                if (building.dead || building.removed || !building.isBuilding || building.transportedBy != null ||
                    !building.x.isFinite() || !building.y.isFinite() || building.team == null
                ) continue
                if (building.team !== viewer &&
                    (!building.isVisibleToAllPlayers || !building.isVisibleToTeam(viewer))
                ) continue
                val originX = building.minimapOriginX
                val originY = building.minimapOriginY
                if (!originX.isFinite() || !originY.isFinite()) continue
                val tileX = ((building.x - originX + 1f) * map.inverseTileWidth).toInt()
                val tileY = ((building.y - originY + 1f) * map.inverseTileHeight).toInt()
                val footprint = building.footprint ?: continue
                if (footprint.left > footprint.right || footprint.top > footprint.bottom) continue
                val firstX = max(left.toLong(), tileX.toLong() + footprint.left)
                val lastX = min(right.toLong(), tileX.toLong() + footprint.right)
                val firstY = max(top.toLong(), tileY.toLong() + footprint.top)
                val lastY = min(bottom.toLong(), tileY.toLong() + footprint.bottom)
                if (firstX > lastX || firstY > lastY) continue
                for (x in firstX.toInt()..lastX.toInt()) for (y in firstY.toInt()..lastY.toInt()) {
                    blocked[(x - left) * height + y - top] = true
                }
            }
            MoveNavigationGrid(
                left * tileWidth.toFloat(), top * tileHeight.toFloat(), width, height,
                tileWidth.toFloat(), tileHeight.toFloat(), blocked,
            )
        }
    }.getOrNull()

    /** Only normal body-steered land motion is modelled in the first implementation. */
    fun motion(unit: OrderableUnit): MoveMotion? = runCatching {
        if (unit.dead || unit.removed || unit.isBuilding || unit.isAirUnit ||
            unit.transportedBy != null || unit.attachedToUnit != null || !unit.canReceiveOrders() ||
            unit.movementType != MovementType.LAND || unit.isMoveSlidingMode || unit.isMoveIgnoringBody ||
            unit.moveYAxisScaling != 1f || unit.moveYAxisScaleReciprocal != 1f || unit.directMoveSpeed != 0f
        ) return@runCatching null
        MoveMotion(
            position = MovePoint(unit.x, unit.y),
            headingDegrees = unit.angle,
            speedFraction = unit.moveThrottle,
            currentTurnRate = unit.currentTurnRate,
            maxSpeed = unit.moveSpeed,
            maxTurnSpeed = unit.turnSpeed,
            turnAcceleration = unit.turnAcceleration,
            acceleration = unit.moveAccelerationSpeed,
            // Original D() is the throttle deceleration in updateMoveThrottle, despite this old mapping name.
            deceleration = unit.buildSpeed,
            reverseSpeedFraction = unit.reverseSpeedPercentage,
            radius = unit.radius,
            formationLeader = unit.inFormation && unit.formationSlotCount <= 1,
        ).takeIf { it.valid }
    }.getOrNull()

    /** Capture a released solver result before the game clips it into the unit's route buffer. */
    fun pendingContinuation(engine: GameEngine, unit: OrderableUnit, goal: MovePoint): NativeMoveContinuation? = runCatching {
        val access = routeAccess(unit, goal) ?: return@runCatching null
        val path = access.rwppMovePendingPath() ?: return@runCatching null
        if (path.isFlowField) return@runCatching null
        val nodes = path.getNodes() ?: return@runCatching null
        if (nodes.size !in LIVE_PATH_CAPACITY..MAX_BASELINE_NODES) return@runCatching null
        val map = engine.map ?: return@runCatching null
        if (!map.mapLoaded || map.tileWidth <= 0 || map.tileHeight <= 0 ||
            map.inverseTileWidth <= 0f || map.inverseTileHeight <= 0f ||
            goal.x < 0f || goal.y < 0f || goal.x >= map.mapWidthInTiles * map.tileWidth.toFloat() ||
            goal.y >= map.mapHeightInTiles * map.tileHeight.toFloat()
        ) return@runCatching null
        val end = nodes.lastOrNull() as? PathSolverNode ?: return@runCatching null
        val exactEnd = end.x.toInt() == (goal.x * map.inverseTileWidth).toInt() &&
            end.y.toInt() == (goal.y * map.inverseTileHeight).toInt()
        val buildRange = approachBuildRange(unit)
        if (!exactEnd && (buildRange == null ||
                MovePoint(end.x * map.tileWidth.toFloat() + map.halfTileWidth,
                    end.y * map.tileHeight.toFloat() + map.halfTileHeight).distanceTo(goal) > buildRange)) return@runCatching null
        val prefix = ArrayList<MovePoint>(LIVE_PATH_CAPACITY)
        val tail = ArrayList<MovePoint>(nodes.size - LIVE_PATH_CAPACITY)
        for ((index, value) in nodes.withIndex()) {
            val node = value as? PathSolverNode ?: return@runCatching null
            if (node.x.toInt() !in 0 until map.mapWidthInTiles || node.y.toInt() !in 0 until map.mapHeightInTiles) {
                return@runCatching null
            }
            val point = MovePoint(node.x * map.tileWidth.toFloat() + map.halfTileWidth,
                node.y * map.tileHeight.toFloat() + map.halfTileHeight)
            if (index < LIVE_PATH_CAPACITY) prefix.add(point)
            else tail.add(if (index == nodes.size - 1 && exactEnd) goal else point)
        }
        NativeMoveContinuation(goal, prefix, tail)
    }.getOrNull()

    /** Returns an ordinary path to this exact MOVE destination, using the verified solver tail when available. */
    fun nativeRoute(unit: OrderableUnit, goal: MovePoint, continuation: NativeMoveContinuation? = null): List<MovePoint>? = runCatching {
        val access = routeAccess(unit, goal) ?: return@runCatching null
        if (access.rwppMovePendingPath() != null) return@runCatching null
        val count = access.rwppMovePathCount()
        val nodes = access.rwppMovePathNodes() ?: return@runCatching null
        if (count !in 1..LIVE_PATH_CAPACITY || count > nodes.size) return@runCatching null
        val truncated = access.rwppMovePathTruncated()
        val join = continuation?.takeIf { truncated && it.goal == goal } ?: if (truncated) return@runCatching null else null
        val prefixOffset = (join?.appliedPrefix?.size ?: count) - count
        if (prefixOffset < 0) return@runCatching null
        val result = ArrayList<MovePoint>(count + 1)
        for (index in 0 until count) {
            val node = nodes[index] ?: return@runCatching null
            val point = MovePoint(node.a, node.b)
            if (!point.finite) return@runCatching null
            if (join != null && point != join.appliedPrefix[prefixOffset + index]) return@runCatching null
            if (result.lastOrNull() != point) result += point
        }
        if (join != null) {
            // At exactly 120 nodes the live tail is already in the goal tile.
            if (join.tail.isEmpty() && unit.currentWaypoint?.type == WaypointType.move) result[result.lastIndex] = goal
            else result.addAll(join.tail)
        }
        val last = result.lastOrNull() ?: return@runCatching null
        approachBuildRange(unit)?.let { range ->
            // The BUILD must resume inside its native range even when MOVE finishes about 7px early.
            // Keep the native reachable approach; never append the blocked building centre as a MOVE.
            val end = result.indexOfFirst { it.distanceTo(goal) <= (range - 10f).coerceAtLeast(1f) }
            return@runCatching if (end >= 0) result.take(end + 1) else null
        }
        // A nearest-reachable fallback is not evidence of a path to the requested goal.
        if (!sameTarget(last.x, last.y, goal)) return@runCatching null
        if (last != goal) result += goal
        result
    }.getOrNull()

    private fun routeAccess(unit: OrderableUnit, goal: MovePoint): NativeMovePathAccess? {
        if (!goal.finite || unit.flowFieldFollower != null || unit.waypointCount < 1) return null
        val waypoint = unit.currentWaypoint ?: return null
        if (waypoint.type !in listOf(WaypointType.move, WaypointType.build) || waypoint.targetUnit != null ||
            !sameTarget(waypoint.targetX, waypoint.targetY, goal)) return null
        val access = unit as? NativeMovePathAccess ?: return null
        return access.takeIf { sameTarget(it.rwppMovePathGoalX(), it.rwppMovePathGoalY(), goal) }
    }

    private fun approachBuildRange(unit: OrderableUnit): Float? {
        val order = unit.currentWaypoint?.takeIf { it.type == WaypointType.build } ?: return null
        val type = order.unitType ?: return null
        return unit.getBuildRange(type).takeIf { it.isFinite() && it > 10f }
    }

    private fun sameTarget(x: Float, y: Float, goal: MovePoint): Boolean =
        x.isFinite() && y.isFinite() && abs(x - goal.x) <= TARGET_EPSILON && abs(y - goal.y) <= TARGET_EPSILON

    private const val TARGET_EPSILON = 0.01f
    private const val LIVE_PATH_CAPACITY = 120
    private const val MAX_BASELINE_NODES = 512
    private const val MAX_CAPTURE_CELLS = 16_384L
    private const val ROI_PADDING_TILES = 8f
}

internal interface NativeMovePathAccess {
    fun rwppMovePathGoalX(): Float
    fun rwppMovePathGoalY(): Float
    fun rwppMovePathTruncated(): Boolean
    fun rwppMovePathCount(): Int
    fun rwppMovePathNodes(): Array<NativePathNode?>?
    fun rwppMovePendingPath(): Path?
}

@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
@AddInterface("io/github/rwpp/game/movement/NativeMovePathAccess")
abstract class NativeMovePathAccessMixin {
    @Accessor("pathGoalX")
    abstract fun rwppMovePathGoalX(): Float

    @Accessor("pathGoalY")
    abstract fun rwppMovePathGoalY(): Float

    @Accessor("pathTruncated")
    abstract fun rwppMovePathTruncated(): Boolean

    @Accessor("activePathCount")
    abstract fun rwppMovePathCount(): Int

    @Accessor("pathNodes")
    abstract fun rwppMovePathNodes(): Array<NativePathNode?>?

    @Accessor("pendingPath")
    abstract fun rwppMovePendingPath(): Path?
}
