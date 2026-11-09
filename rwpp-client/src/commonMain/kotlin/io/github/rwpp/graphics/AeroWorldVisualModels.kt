/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.corrodinggames.rts.game.units.MovementType
import io.github.rwpp.game.hud.hideFullHealthBarDuringMultiSelection
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal enum class AeroOrderKind { MOVE, ATTACK, PATROL, GUARD, BUILD, REPAIR, RECLAIM, RALLY }

internal fun aeroOrderKind(type: String): AeroOrderKind? = when (type.lowercase()) {
    "move", "loadinto", "loadup", "unloadat", "touchtarget" -> AeroOrderKind.MOVE
    "attack", "attackmove", "setpassivetarget" -> AeroOrderKind.ATTACK
    "patrol" -> AeroOrderKind.PATROL
    "guard", "guardat", "follow" -> AeroOrderKind.GUARD
    "build" -> AeroOrderKind.BUILD
    "repair" -> AeroOrderKind.REPAIR
    "reclaim" -> AeroOrderKind.RECLAIM
    else -> null
}

/** Replay UI control is broader than the viewer's team permissions, so do not use it as a visibility gate. */
internal fun aeroReplayOrderAccess(
    replaying: Boolean,
    showAllOrders: Boolean,
    sameTeam: Boolean,
    sharedControl: Boolean,
    nativeCanControl: Boolean
): Boolean = if (replaying) showAllOrders || sameTeam || sharedControl else nativeCanControl

/** A replay-only privacy gate for destinations issued by units outside the viewed player's control. */
internal fun aeroReplayWaypointDestinationVisible(
    fogEnabled: Boolean,
    fogGrid: Array<ByteArray>?,
    tileWidth: Int,
    tileHeight: Int,
    mapWidth: Int,
    mapHeight: Int,
    x: Float,
    y: Float,
    targetUnitVisible: Boolean,
    requireCurrentVision: Boolean
): Boolean {
    if (!targetUnitVisible) return false
    if (!requireCurrentVision || !fogEnabled || fogGrid == null) return true
    if (!x.isFinite() || !y.isFinite() || tileWidth <= 0 || tileHeight <= 0) return false
    val tileX = floor(x / tileWidth).toInt()
    val tileY = floor(y / tileHeight).toInt()
    return !(tileX !in 0 until mapWidth || tileY !in 0 until mapHeight ||
            tileX >= fogGrid.size || tileY >= fogGrid[tileX].size) && fogGrid[tileX][tileY].toInt() < 5
}

/** World-space footprint of a native placement grid, used by the Aero build effect. */
internal data class AeroPlacementGrid(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val stepX: Float,
    val stepY: Float,
)

internal data class AeroOrder(
    val from: Offset,
    val to: Offset,
    val attack: Boolean,
    val count: Int = 1,
    val kind: AeroOrderKind = if (attack) AeroOrderKind.ATTACK else AeroOrderKind.MOVE,
    val step: Int = 0,
    val team: Int = 0,
    val preview: Boolean = false,
    val radius: Float = 16f,
    val progress: Float = -1f,
    val grid: AeroPlacementGrid? = null
)

/** Preserve kind, owner and queue position when merging same-destination orders. */
internal fun aeroOrderGroups(orders: List<AeroOrder>, merge: Boolean): List<AeroOrder> =
    if (!merge) orders else orders.groupBy {
        listOf(
            floor(it.to.x / 24f).toInt(),
            floor(it.to.y / 24f).toInt(),
            it.kind,
            it.step,
            it.team,
            it.preview,
            it.grid
        )
    }
        .values.map { group ->
            val count = group.sumOf { it.count }
            group.first().copy(
                from = Offset(
                    group.sumOf { (it.from.x * it.count).toDouble() }.toFloat() / count,
                    group.sumOf { (it.from.y * it.count).toDouble() }.toFloat() / count
                ), count = count
            )
        }

internal fun aeroHealthVisible(selected: Boolean, hovered: Boolean, alwaysShow: Boolean, hp: Float): Boolean =
    aeroHealthVisible(selected, hovered, alwaysShow, hp, selectedUnitCount = 1)

internal fun aeroHealthVisible(
    selected: Boolean,
    hovered: Boolean,
    alwaysShow: Boolean,
    hp: Float,
    selectedUnitCount: Int,
): Boolean {
    return !hideFullHealthBarDuringMultiSelection(selected, selectedUnitCount, hp, 1f) && (selected || hovered || alwaysShow && hp < 1f)
}

/** Slightly lighter outline for the native drag-selection rectangle. */
internal const val AERO_DRAG_SELECTION_STROKE_WIDTH = 0.8f

internal fun aeroAttackRangeVisible(
    movement: MovementType,
    building: Boolean,
    mode: String,
    buildings: Boolean,
    forced: Boolean
): Boolean =
    forced || if (building) buildings else when (movement) {
        MovementType.AIR -> mode == "Air" || mode == "All"
        MovementType.LAND, MovementType.HOVER, MovementType.OVER_CLIFF, MovementType.OVER_CLIFF_WATER -> mode == "Land" || mode == "All"
        else -> mode == "All"
    }

/** Vanilla T1 turret range (`assets/units/turrets/turret_t1.ini`). */
internal const val AERO_MIN_ATTACK_RANGE = 165f

/** Apply the common vanilla-turret minimum before per-unit, building, or forced visibility rules. */
internal fun aeroAttackRangeAllowed(
    range: Float,
    movement: MovementType,
    building: Boolean,
    mode: String,
    buildings: Boolean,
    forced: Boolean
): Boolean = range >= AERO_MIN_ATTACK_RANGE &&
        aeroAttackRangeVisible(movement, building, mode, buildings, forced)

internal fun damageTrail(previous: Float, current: Float, elapsed: Float): Float =
    max(current, previous - elapsed.coerceIn(0f, 0.1f) * 0.65f)

/** Expensive unit queries run at 30 Hz while camera projection still follows every display frame. */
internal class WorldVisualRefreshSchedule {
    private var sampledAt = Long.MIN_VALUE
    fun reset() {
        sampledAt = Long.MIN_VALUE
    }

    fun due(now: Long, force: Boolean = false): Boolean {
        if (!force && sampledAt != Long.MIN_VALUE && now >= sampledAt && now - sampledAt < 33_333_333L) return false
        sampledAt = now
        return true
    }
}

/** Clip long paths before generating arrow geometry. End markers retain their real position. */
internal fun clipAeroSegment(from: Offset, to: Offset, bounds: Rect): Pair<Offset, Offset>? {
    if (!from.x.isFinite() || !from.y.isFinite() || !to.x.isFinite() || !to.y.isFinite()) return null
    val delta = to - from
    var start = 0f
    var end = 1f
    fun edge(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val t = q / p
        if (p < 0f) start = max(start, t) else end = min(end, t)
        return start <= end
    }
    if (!edge(-delta.x, from.x - bounds.left) || !edge(delta.x, bounds.right - from.x) ||
        !edge(-delta.y, from.y - bounds.top) || !edge(delta.y, bounds.bottom - from.y)
    ) return null
    return (from + delta * start) to (from + delta * end)
}

internal data class SelectedVisual(
    val center: Offset, val radius: Float, val hp: Float,
    val trail: Float, val shield: Float, val color: Color, val entrance: Float = 0f,
    val selected: Boolean = true, val hovered: Boolean = false, val healthVisible: Boolean = true,
    val construction: Float = -1f, val secondary: Float = -1f, val shieldVisible: Boolean = shield > 0f,
    val glowHighlight: Boolean = false,
    /** Selection brackets follow the native ally/enemy relation, the health fill keeps [color]. */
    val selectionColor: Color = color,
    val barRadius: Float = radius, val barScaledDown: Boolean = false, val barMini: Boolean = false,
    val transportUsed: Int = -1, val transportCapacity: Int = -1,
    val secondaryRecharging: Boolean = false,
    val improvedHealthStyle: Boolean = false,
)

internal data class WorldVisuals(
    val selected: List<SelectedVisual> = emptyList(),
    val orders: List<AeroOrder> = emptyList(),
    val camera: Offset = Offset.Zero,
    val zoom: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    val phase: Float = 0f,
    val ranges: List<AeroRangeGroup> = emptyList(),
    val damageNumbers: List<AeroDamageNumber> = emptyList(),
    val detailedRangeStyle: Boolean = true,
    val production: List<AeroProductionVisual> = emptyList(),
    val pausedLabel: String = "",
    val waitingLabel: String = "",
    val collisionBounds: List<AeroCollisionVisual> = emptyList(),
    val targetLines: List<Pair<Offset, Offset>> = emptyList(),
)

internal data class AeroDamageNumber(
    val unitId: Int, val center: Offset, val amount: Float,
    val bornAt: Long, val lane: Int, val age: Float = 0f
)

internal data class AeroProductionVisual(
    val center: Offset, val radius: Float, val name: String,
    val remainingSeconds: Int?, val count: Int, val progress: Float, val paused: Boolean,
    val color: Color, val icon: ImageBitmap? = null, val waiting: Boolean = false
)
