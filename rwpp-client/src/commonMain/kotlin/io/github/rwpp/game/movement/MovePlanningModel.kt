/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import kotlin.math.hypot

internal const val MAX_ASSISTED_MOVE_TARGETS = 24

internal data class MovePoint(val x: Float, val y: Float) {
    fun distanceTo(other: MovePoint): Float = hypot(x - other.x, y - other.y)
    val finite: Boolean get() = x.isFinite() && y.isFinite()
}

/** Speeds and durations use native simulation units, not wall-clock seconds. */
internal data class MoveMotion(
    val position: MovePoint,
    val headingDegrees: Float,
    val speedFraction: Float,
    val currentTurnRate: Float,
    val maxSpeed: Float,
    val maxTurnSpeed: Float,
    val turnAcceleration: Float,
    val acceleration: Float,
    val deceleration: Float,
    val reverseSpeedFraction: Float,
    val radius: Float,
    val formationLeader: Boolean = false,
) {
    val valid: Boolean get() = position.finite && headingDegrees.isFinite() && speedFraction.isFinite() &&
            currentTurnRate.isFinite() && maxSpeed.isFinite() && maxSpeed > 0f &&
            maxTurnSpeed.isFinite() && maxTurnSpeed > 0f && turnAcceleration.isFinite() &&
            acceleration.isFinite() && acceleration > 0f && deceleration.isFinite() && deceleration > 0f &&
            reverseSpeedFraction.isFinite() && radius.isFinite() && radius > 0f
}

/** Targets exclude the start and include the user's original destination as the last point. */
internal data class MoveRoutePlan(
    val targets: List<MovePoint>,
    val nativeEstimatedTime: Float,
    val plannedEstimatedTime: Float,
    val terrainClearance: Float,
)
