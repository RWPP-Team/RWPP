/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.mode

import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.CommandQueueEvent

/**
 * Host-authoritative room rule for reclaim orders. Native commands remain the source of truth:
 * the server rejects disallowed commands before they enter its pending queue or are broadcast.
 */
class RecyclingRulesMode(
    val disableAllRecycling: Boolean,
    disabledUnitNames: Set<String> = emptySet(),
    override val id: String = ID,
    override val displayName: String = "Recycling restrictions",
    override val order: Int = 0,
    override val requires: Set<String> = emptySet(),
    override val conflictsWith: Set<String> = emptySet(),
) : GameMode {
    override val requiresHostAuthority: Boolean = true
    val disabledUnitNames: Set<String> = disabledUnitNames.map(::normalizeRecyclingUnitName)
        .filter(String::isNotBlank)
        .toSet()

    override fun onEnable(context: GameModeContext) {
        if (!disableAllRecycling && disabledUnitNames.isEmpty()) return

        GlobalEventChannel.filter(CommandQueueEvent::class).subscribeAlways(context.eventOwner) { event ->
            if (!context.session.hasNativeAuthority()) return@subscribeAlways

            val command = event.command
            val waypoint = command.waypoint
            val reclaimWaypoint = waypoint?.type == WaypointType.reclaim
            val actionId = command.actionId?.getId()
            val isReclaimAction = actionId == RECLAIM_TARGET_ACTION_ID
            if (!reclaimWaypoint && !isReclaimAction) return@subscribeAlways
            if (disableAllRecycling) {
                event.cancel()
                return@subscribeAlways
            }
            if (disabledUnitNames.isEmpty()) return@subscribeAlways

            val actionTargetName = runCatching {
                command.targetUnit?.getUnitTypeInterface()?.internalName
            }.getOrNull()
            val waypointTargetName = if (!reclaimWaypoint) {
                null
            } else {
                runCatching {
                    waypoint?.targetUnit?.getUnitTypeInterface()?.internalName ?: run {
                        // Network-decoded Waypoints retain only the target ID until issueCommand().
                        // Resolve through the same native lookup without mutating the Waypoint.
                        val targetId = waypoint?.unitId ?: -1L
                        if (targetId >= 0L) {
                            GameObject.getUnitFromId(targetId, true)?.getUnitTypeInterface()?.internalName
                        } else null
                    }
                }.getOrNull()
            }

            // Network-decoded waypoints retain only the target ID until issueCommand(). Resolve
            // through the native lookup without mutation. A c_2 target action can also arrive
            // without a resolvable target unit, so both native reclaim forms fail closed.
            val unresolvedReclaimTarget =
                (reclaimWaypoint && waypointTargetName == null) ||
                        (isReclaimAction && actionTargetName == null)

            if (
                shouldCancelRecyclingCommand(
                    actionId = actionId,
                    isReclaimWaypoint = reclaimWaypoint,
                    disableAllRecycling = disableAllRecycling,
                    disabledUnitNames = disabledUnitNames,
                    actionTargetName = actionTargetName,
                    waypointTargetName = waypointTargetName,
                    reclaimTargetUnresolved = unresolvedReclaimTarget,
                )
            ) {
                event.cancel()
            }
        }
    }

    companion object {
        const val ID = "rwpp:recycling-rules"

        /** Native ReclaimTargetAction uses this stable ActionId for target interactions. */
        const val RECLAIM_TARGET_ACTION_ID = "c_2"
    }
}

internal fun shouldCancelRecyclingCommand(
    actionId: String?,
    isReclaimWaypoint: Boolean,
    disableAllRecycling: Boolean,
    disabledUnitNames: Set<String>,
    actionTargetName: String?,
    waypointTargetName: String?,
    reclaimTargetUnresolved: Boolean,
): Boolean {
    val isReclaimAction = actionId == RecyclingRulesMode.RECLAIM_TARGET_ACTION_ID
    if (!isReclaimWaypoint && !isReclaimAction) return false
    if (disableAllRecycling) return true
    if (disabledUnitNames.isEmpty()) return false
    if (actionTargetName?.let(::normalizeRecyclingUnitName)?.let(disabledUnitNames::contains) == true) return true
    if (waypointTargetName?.let(::normalizeRecyclingUnitName)?.let(disabledUnitNames::contains) == true) return true

    // A reclaim command without a known target cannot be proven to comply with a specific-unit
    // restriction. Fail closed so missing IDs and targetless native reclaim variants cannot bypass it.
    return reclaimTargetUnresolved
}

internal fun normalizeRecyclingUnitName(value: String): String =
    value.trim().removePrefix("u_").lowercase()
