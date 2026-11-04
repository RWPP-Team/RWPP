/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.WaypointType
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.logic.selection.*
import com.corrodinggames.rts.game.units.Unit as NativeUnit

/** Explicit local player operations. Commands, permissions and transport simulation remain native. */
internal object NativeTacticalActions {
    private data class PendingSeat(
        val passenger: OrderableUnit, val carrier: OrderableUnit, val issuedAt: Long, var observed: Boolean = false,
    )

    private val pendingSeats = linkedMapOf<Long, PendingSeat>()
    private var mapOwner: Any? = null

    fun reset() {
        pendingSeats.clear(); mapOwner = null
    }

    fun selectLowHealth(engine: GameEngine = GameEngine.getInstance()): Int {
        val ui = engine.interfaceEngine
        val selection = ui.selectedUnits.filterIsInstance<NativeUnit>().toList()
        val selectedScope = selection.isNotEmpty()
        val source = if (selectedScope) selection else NativeUnit.unitList.filterIsInstance<NativeUnit>()
        val zoom = engine.zoomScale.coerceAtLeast(0.01f)
        val result = source.filterIsInstance<OrderableUnit>().filter {
            !it.dead && it.transportedBy == null && it.attachedToUnit == null &&
                    ui.canPlayerControlUnit(it) && isLowHealth(it.hp, it.maxHp) &&
                    (selectedScope || intersectsSelectionViewport(
                        it.x,
                        it.y - it.height,
                        it.radius,
                        engine.cameraRenderX,
                        engine.cameraRenderY,
                        engine.screenWidth / zoom,
                        engine.screenHeight / zoom
                    ))
        }
        ui.cancelActiveAction()
        ui.clearUnitSelection()
        result.forEach(ui::selectUnit)
        ui.showImportantScreenMessage(readI18n("rwpp.tactical.lowHealthResult", result.size))
        return result.size
    }

    fun autoLoad(engine: GameEngine = GameEngine.getInstance()): Int {
        val ui = engine.interfaceEngine
        if (engine.map == null || engine.replayEngine.isPlayingReplay || engine.playerTeam?.isSpectator != false) return 0
        if (mapOwner !== engine.map) {
            pendingSeats.clear(); mapOwner = engine.map
        }
        val now = System.nanoTime() / 1_000_000L
        pendingSeats.entries.removeAll { (_, seat) ->
            val waypoint = seat.passenger.currentWaypoint
            val underway = waypoint?.type == WaypointType.loadInto && waypoint.targetUnit === seat.carrier
            if (underway) seat.observed = true
            seat.passenger.dead || seat.carrier.dead || seat.passenger.transportedBy != null ||
                    !ui.canPlayerControlUnit(seat.passenger) || (seat.observed && !underway) ||
                    (!seat.observed && now - seat.issuedAt > 30_000L)
        }
        val selected = ui.selectedUnits.filterIsInstance<OrderableUnit>().filter {
            !it.dead && it.transportedBy == null && it.attachedToUnit == null && ui.canPlayerControlUnit(it)
        }
        val carriers = selected.filter { it.canTransportUnits() && capacity(it) > 0 }
        if (carriers.isEmpty()) {
            ui.showImportantScreenMessage(readI18n("rwpp.tactical.selectTransport"))
            return 0
        }
        val carrierById = carriers.associateBy { it.id }
        val reservations = linkedMapOf<Long, Pair<OrderableUnit, OrderableUnit>>()
        // Include inbound orders from passengers outside the current selection as well as
        // this client's commands which have not reached their simulation frame yet.
        NativeUnit.unitList.filterIsInstance<OrderableUnit>().forEach { passenger ->
            if (passenger.dead || passenger.transportedBy != null) return@forEach
            val waypoint = passenger.currentWaypoint ?: return@forEach
            val target = waypoint.targetUnit as? OrderableUnit ?: return@forEach
            if (waypoint.type == WaypointType.loadInto && target.id in carrierById) {
                reservations[passenger.id] = passenger to target
            }
        }
        pendingSeats.values.filter { it.carrier.id in carrierById }.forEach {
            reservations[it.passenger.id] = it.passenger to it.carrier
        }
        val reservedSlots = reservations.values.groupBy { it.second.id }.mapValues { (_, seats) ->
            seats.sumOf { (passenger, carrier) -> slotCost(carrier, passenger) }
        }
        val spaces = carriers.map {
            TransportSpace(
                it.id,
                (capacity(it) - usedSlots(it) - (reservedSlots[it.id] ?: 0)).coerceAtLeast(0), it.x, it.y
            )
        }
        val passengers = selected.filter {
            it.id !in carrierById && it.id !in reservations && !it.isBuilding
        }
        val candidates = passengers.map { passenger ->
            TransportCandidate(passenger.id, passenger.x, passenger.y, carriers.filter {
                it.canLoad(passenger, false)
            }.associate { it.id to slotCost(it, passenger) })
        }
        val assignments = assignTransportSeats(spaces, candidates)
        val passengerById = passengers.associateBy { it.id }
        assignments.groupBy { it.carrierId }.forEach { (carrierId, group) ->
            val carrier = carrierById.getValue(carrierId)
            val command = ui.getPlayerCommand()
            group.forEach { assignment ->
                val passenger = passengerById.getValue(assignment.passengerId)
                command.addUnit(passenger)
                pendingSeats[passenger.id] = PendingSeat(passenger, carrier, now)
            }
            command.setLoadWaypoint(carrier)
        }
        ui.showImportantScreenMessage(
            readI18n(
                "rwpp.tactical.loadResult", assignments.size,
                (passengers.size - assignments.size).coerceAtLeast(0)
            )
        )
        return assignments.size
    }

    private fun capacity(carrier: OrderableUnit): Int = if (carrier is CustomUnit)
        carrier.meta.maxTransportingUnits else carrier.transportSlotsForDisplay

    private fun usedSlots(carrier: OrderableUnit): Int = if (carrier is CustomUnit)
        carrier.usedTransportSlots else carrier.usedTransportSlotsForDisplay.coerceAtLeast(0)

    private fun slotCost(carrier: OrderableUnit, passenger: OrderableUnit): Int =
        if (carrier is CustomUnit && carrier.meta.transportUnitsEachUnitAlwaysUsesSingleSlot) 1
        else passenger.transportSlotsNeeded.coerceAtLeast(1)
}
