/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit as NativeUnit
import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.game.units.WaypointType

internal fun Waypoint.unfinishedConstructionTarget(): NativeUnit? =
    if (type == WaypointType.repair) targetUnit?.takeIf {
        it.isBuilding && it.buildProgress in 0f..<1f && !it.dead && !it.removed &&
            it.transportedBy == null && it.resourceRate == 0f
    } else null

internal fun Waypoint.supportsMoveAssist(): Boolean = when (type) {
    WaypointType.move, WaypointType.build -> targetUnit == null
    WaypointType.repair -> unfinishedConstructionTarget() != null
    else -> false
}

internal fun Waypoint.friendlyConstructionTarget(team: Team?): NativeUnit? =
    unfinishedConstructionTarget()?.takeIf { target ->
        target.team?.let { team?.isSameTeam(it) } == true
    }

internal fun Waypoint.constructionRange(worker: OrderableUnit): Float? = when (type) {
    WaypointType.build -> unitType?.let(worker::getBuildRange)
    WaypointType.repair -> friendlyConstructionTarget(worker.team)
        ?.takeIf(worker::canRepairUnit)
        ?.let { worker.getRepairRange(it).toFloat() }
    else -> null
}
