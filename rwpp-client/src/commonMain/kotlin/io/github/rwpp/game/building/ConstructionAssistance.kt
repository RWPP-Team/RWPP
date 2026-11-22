/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.building

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.EditorUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.logic.GameVisualSettings
import io.github.rwpp.logic.hud.GameUiPauseState
import io.github.rwpp.mixin.building.NativeBuildingPlanningCommandAccess
import com.corrodinggames.rts.game.units.Unit as NativeUnit

/** Local follow-up for builders finishing their own orders. Every assist is an ordinary queued repair. */
internal object ConstructionAssistance {
    private class Work {
        var finishedAt: Int? = null
        var assistTarget: NativeUnit? = null
        var sentAt = 0
    }

    private var map: Any? = null
    private var team: Team? = null
    private val builders = LinkedHashMap<OrderableUnit, Work>()
    private var nextCheck = 0

    fun onCommandApplied(command: CommandControllerCommand) {
        val engine = GameEngine.getInstance() ?: return
        if (!syncWorld(engine) || command.isReplayCommand) return
        val order = command.waypoint
        if (order == null && !command.clearWaypointsFirst) return
        val localBuild = order?.type == WaypointType.build && !command.hasSystemAction &&
            command.team === team && (command.player == null || command.player === team)
        if (!localBuild && builders.isEmpty()) return
        val units = (command as? NativeBuildingPlanningCommandAccess)?.rwppPlanningCommandUnits() ?: return
        for (entry in units) {
            val worker = entry as? OrderableUnit ?: continue
            if (localBuild && canAssist(engine, worker)) {
                builders[worker] = Work()
            } else {
                val work = builders[worker] ?: continue
                val isOwnAssist = work.assistTarget != null && command.team === team &&
                    (command.player == null || command.player === team) && command.queueOrder &&
                    order?.type == WaypointType.repair && order.targetUnit === work.assistTarget
                if (isOwnAssist) work.assistTarget = null else builders.remove(worker)
            }
        }
    }

    fun beforeWaypointRemoved(worker: OrderableUnit) {
        val work = builders[worker] ?: return
        if (GameVisualSettings.get()?.constructionAssistanceEnabled != true) return
        val current = worker.currentWaypoint ?: return
        val target = current.targetUnit ?: return
        if (current.type != WaypointType.repair || !target.isBuilding || target.dead || target.removed ||
            target.team !== team || target.buildProgress < 1f
        ) return
        work.finishedAt = GameEngine.getInstance().gameTimeMillis
    }

    fun update(engine: GameEngine) {
        if (!syncWorld(engine) || builders.isEmpty() || engine.gamePaused ||
            GameUiPauseState.blocked || engine.interfaceEngine.pauseMenuOpen || engine.networkEngine.shouldGameBePaused()
        ) return
        val now = engine.gameTimeMillis
        if (now < nextCheck) return
        nextCheck = now + 100

        builders.entries.removeAll { (worker, work) ->
            !canAssist(engine, worker) || work.assistTarget?.let { now - work.sentAt > 5_000 } == true ||
                worker.isIdle && work.finishedAt == null && work.assistTarget == null
        }
        val sites = LinkedHashSet<NativeUnit>()
        var approachingSite = false
        for (worker in builders.keys) {
            val order = worker.currentWaypoint ?: continue
            if (order.type == WaypointType.build) approachingSite = true
            val site = order.targetUnit ?: continue
            if (order.type == WaypointType.repair && site.isBuilding && site.buildProgress < 1f &&
                !site.dead && !site.removed && site.team === team
            ) sites.add(site)
        }
        for ((worker, work) in builders) {
            val finishedAt = work.finishedAt ?: continue
            if (!worker.isIdle || work.assistTarget != null) continue
            if (sites.isEmpty()) {
                if (!approachingSite && now - finishedAt >= 1_000) work.finishedAt = null
                continue
            }
            if (hasPendingOrder(engine, worker)) continue
            val site = sites.asSequence().filter {
                worker.canRepairUnit(it) && (worker.canActIndependently() ||
                    distanceSquared(worker, it) <= worker.getRepairRange(it).toFloat().let { range -> range * range })
            }.minWithOrNull(compareBy({ distanceSquared(worker, it) }, { it.id })) ?: continue

            work.assistTarget = site
            work.sentAt = now
            work.finishedAt = null
            engine.interfaceEngine.getPlayerCommand().apply {
                queueOrder = true
                addUnit(worker)
                setRepairWaypoint(site)
            }
        }
    }

    private fun syncWorld(engine: GameEngine): Boolean {
        if (GameVisualSettings.get()?.constructionAssistanceEnabled != true) {
            builders.clear()
            nextCheck = 0
            map = null
            team = null
            return false
        }
        val currentMap = engine.map
        val currentTeam = engine.playerTeam
        if (currentMap !== map || currentTeam !== team || engine.replayEngine.isPlayingReplay) {
            builders.clear()
            nextCheck = 0
            map = currentMap
            team = currentTeam
        }
        return currentMap?.mapLoaded == true && currentTeam?.isSpectator == false &&
            !engine.replayEngine.isPlayingReplay
    }

    private fun canAssist(engine: GameEngine, worker: OrderableUnit): Boolean =
        worker !is EditorUnit && !worker.dead && !worker.removed && worker.transportedBy == null &&
            worker.team === team && engine.interfaceEngine.canPlayerControlUnit(worker)

    private fun hasPendingOrder(engine: GameEngine, worker: OrderableUnit): Boolean {
        val controller = engine.commandController
        return sequenceOf(controller.localPendingCommands, controller.networkPendingCommands, controller.issuedCommands)
            .flatMap { it.asSequence().filterIsInstance<CommandControllerCommand>() }
            .any { command ->
                val units = command as? NativeBuildingPlanningCommandAccess
                (command.waypoint != null || command.clearWaypointsFirst) && units != null &&
                    (worker in units.rwppPlanningCommandUnits() || worker.id in units.rwppPlanningPendingUnitIds())
            }
    }

    private fun distanceSquared(worker: OrderableUnit, target: NativeUnit): Float {
        val dx = worker.x - target.x
        val dy = worker.y - target.y
        return dx * dx + dy * dy
    }
}
