/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.util.Reflect
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.selection.*
import kotlin.math.roundToInt
import com.corrodinggames.rts.game.units.Unit as NativeUnit

/** Records actual applied group build orders; selection may change before the low-frequency send. */
internal object NativeBuilderGroupAssistance {
    private data class Cohort(val units: List<OrderableUnit>, val changedAt: Long)

    private val pending = linkedMapOf<List<Long>, Cohort>()
    private var mapOwner: Any? = null
    private var lastSentAt = Long.MIN_VALUE
    private var nextInspectAt = 0L

    fun onCommandApplied(command: CommandControllerCommand) {
        val engine = GameEngine.getInstance()
        if (engine.replayEngine.isPlayingReplay || engine.playerTeam?.isSpectator != false) return
        if (command.team !== engine.playerTeam || (command.player != null && command.player !== engine.playerTeam)) return
        if (!appKoin.get<Settings>().builderQueueProtectionEnabled) return
        resetForMap(engine.map)
        val units = (Reflect.get<Iterable<*>>(command, "units") ?: emptyList<Any>())
            .filterIsInstance<OrderableUnit>()
        if (units.isEmpty()) return
        val ids = units.mapTo(hashSetOf()) { it.id }
        // A newly applied user order withdraws its units from any pending older group. This is
        // observation only; the native command has already applied all queue/permission rules.
        val previous = pending.values.toList()
        pending.clear()
        for (cohort in previous) {
            val remaining = cohort.units.filter { it.id !in ids }
            if (remaining.size > 1) pending[remaining.map { it.id }.sorted()] = cohort.copy(units = remaining)
        }
        if (command.waypoint?.type != WaypointType.build) return
        val builders = units.filter { !it.dead && it.isBuilder && engine.interfaceEngine.canPlayerControlUnit(it) }
        if (builders.size < 2) return
        val now = System.nanoTime() / 1_000_000L
        pending[builders.map { it.id }.sorted()] = Cohort(builders, now)
        // A bounded list of recently issued groups, not a world/unit scan every frame.
        while (pending.size > 32) pending.remove(pending.keys.first())
    }

    fun update(settings: Settings, blocked: Boolean) {
        if (!settings.builderQueueProtectionEnabled) {
            reset(); return
        }
        val engine = GameEngine.getInstance()
        resetForMap(engine.map)
        if (blocked || pending.isEmpty() || engine.replayEngine.isPlayingReplay || engine.playerTeam?.isSpectator != false) return
        val now = System.nanoTime() / 1_000_000L
        if (now < nextInspectAt) return
        nextInspectAt = now + 250L
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < BUILDER_GUARD_ORDER_INTERVAL_MS) return
        for ((key, cohort) in pending.toMap()) {
            if (now - cohort.changedAt < BUILDER_GUARD_SETTLE_MS) continue
            val builders = cohort.units.filter {
                !it.dead && it.transportedBy == null && engine.interfaceEngine.canPlayerControlUnit(it)
            }
            val plan = planBuilderQueueProtection(builders.map { BuilderQueueMember(it.id, queuePlan(it)) })
            if (plan == null) {
                // A lone building is ordinary vanilla behavior. Keep a short window so queued
                // placements arriving in later network steps can extend the same group.
                if (now - cohort.changedAt > 5_000L) pending.remove(key)
                continue
            }
            val leader = builders.firstOrNull { it.id == plan.leaderId } ?: continue
            val followers = builders.filter { it.id in plan.followerIds }
            if (followers.isEmpty()) {
                pending.remove(key); continue
            }
            // This ordinary native guard order replaces only duplicate queues in the same
            // explicitly commanded group. Native guard builders assist the leader's work beam.
            val command = engine.interfaceEngine.getPlayerCommand()
            followers.forEach(command::addUnit)
            command.setGuardWaypoint(leader)
            lastSentAt = now
            pending.remove(key)
            logger().info(
                "Builder queue protection: leader=${leader.id}, followers=${followers.size}, sites=${
                    queuePlan(
                        leader
                    )?.size
                }"
            )
            break
        }
    }

    private fun queuePlan(builder: OrderableUnit): List<BuilderQueueSite>? {
        val sites = ArrayList<BuilderQueueSite>()
        for (index in 0 until builder.waypointCount) {
            val waypoint = builder.getWaypoint(index) ?: return null
            when (waypoint.type) {
                WaypointType.build -> {
                    val type = waypoint.unitType ?: return null
                    val template = NativeUnit.getPlaceholderOrDefault(type)
                    val map = GameEngine.getInstance().map ?: return null
                    val x = snappedBuilderSiteCoordinate(waypoint.targetX, template.minimapOriginX, map.tileWidth)
                    val y = snappedBuilderSiteCoordinate(waypoint.targetY, template.minimapOriginY, map.tileHeight)
                    sites.add(BuilderQueueSite(type.internalName, x, y, waypoint.buildTechLevel))
                }

                WaypointType.repair -> {
                    val target = waypoint.targetUnit ?: return null
                    if (!target.isBuilding || target.dead) return null
                    if (target.buildProgress >= 1f) {
                        if (target.hp < target.maxHp) return null
                        continue
                    }
                    sites.add(
                        BuilderQueueSite(
                            target.unitTypeInterface.internalName,
                            target.x.roundToInt(),
                            target.y.roundToInt(),
                            (target as? OrderableUnit)?.techLevel ?: 1
                        )
                    )
                }

                else -> return null
            }
        }
        return sites
    }

    private fun resetForMap(map: Any?) {
        if (mapOwner !== map) {
            reset(); mapOwner = map
        }
    }

    private fun reset() {
        pending.clear(); mapOwner = null; lastSentAt = Long.MIN_VALUE; nextInspectAt = 0L
    }
}
