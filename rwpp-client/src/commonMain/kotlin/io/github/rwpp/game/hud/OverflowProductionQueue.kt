/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import android.graphics.PointF
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.IndirectedAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.BuildQueueItem
import com.corrodinggames.rts.game.units.buildings.FactoryInterface
import com.corrodinggames.rts.game.units.custom.price.CustomPrice
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import io.github.rwpp.logic.GameVisualSettings
import io.github.rwpp.mixin.building.NativeBuildingPlanningCommandAccess

/** Local intent only. Each accepted order is sent through the ordinary native command controller. */
internal object OverflowProductionQueue {
    private const val LIMIT = 256
    // Vanilla can discard client commands without an echo during its 10-second rate-limit window.
    private const val ECHO_RECHECK_NANOS = 12_000_000_000L

    private val pending = linkedMapOf<OrderableUnit, ArrayDeque<ActionId>>()
    private var map: NativeMap? = null
    private var team: Team? = null
    private class Dispatch(
        val owners: List<OrderableUnit>,
        val actionId: ActionId,
        val command: CommandControllerCommand,
    ) {
        val ownerIds = owners.mapTo(HashSet()) { it.id }
        var executing: CommandControllerCommand? = null
        val queuedBefore = IntArray(owners.size)
        var recheckAt: Long? = null
        var confirmation: PointF? = null
    }

    private class ReadyBatch(val action: SpecialAction) {
        val owners = mutableListOf<OrderableUnit>()
    }

    private val inFlight = linkedMapOf<ActionId, Dispatch>()
    private var confirmationSequence = 0
    private var updating = false
    private var nextUpdateAt = 0L
    private val updateTask = Runnable {
        val engine = GameEngine.getInstance() ?: return@Runnable
        synchronized(engine.gameUpdateLock) { update(engine) }
    }

    private val enhanced get() = GameVisualSettings.get()?.enhancedReinforceTroops == true
    private val enabled get() = GameVisualSettings.get()?.overflowProductionQueue == true

    fun supports(action: SpecialAction, units: List<GameUnit>): Boolean {
        if (!enabled || !isProductionAction(action)) return false
        val enhancedProduction = enhanced
        return owners(action, units).any { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@any false
            canEnqueue(admission(owner, owned), enhancedProduction)
        }
    }

    fun pendingCount(action: SpecialAction, units: List<GameUnit>): Int {
        if (!enabled || pending.isEmpty() || !isProductionAction(action)) return 0
        return owners(action, units).sumOf { owner -> pending[owner]?.count { it == action.storedActionId } ?: 0 }
    }

    /** A read-only snapshot of waiting orders for the current selection. Caller holds the game update lock. */
    fun selectedWaitingOrders(engine: GameEngine, selection: List<GameUnit>): Map<String, Int> {
        if (!enabled || map !== engine.map || team !== engine.playerTeam ||
            pending.isEmpty() || selection.isEmpty()
        ) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        for ((owner, requests) in pending) {
            if (owner !in selection || !canControl(engine, owner)) continue
            for ((id, count) in requests.groupingBy { it }.eachCount()) {
                val action = resolveQueueAction(owner, id) ?: continue
                val name = action.unitType?.displayName
                    ?: action.getDisplayNameForUnit(owner).takeIf { it.isNotBlank() } ?: continue
                counts[name] = (counts[name] ?: 0) + count
            }
        }
        return counts
    }

    fun pendingCountForFactory(unit: GameUnit): Int =
        if (!enabled || unit !is OrderableUnit) 0 else pending[unit]?.size ?: 0

    internal fun canEnqueue(state: Admission, enhancedProduction: Boolean): Boolean =
        when (state) {
            Admission.Resources -> true
            Admission.Ready, Admission.Waiting -> enhancedProduction
            Admission.Blocked -> false
        }

    fun enqueue(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine)) return false
        val enhancedProduction = enhanced
        val assignOne = action.isUnitSpecificAction
        val candidates = owners(action, units).filter { canControl(engine, it) }.mapNotNull { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@mapNotNull null
            owner to admission(owner, owned)
        }
        val ready = !enhancedProduction && candidates.any { it.second == Admission.Ready }
        val waiting = candidates.filter { (_, state) ->
            canEnqueue(state, enhancedProduction)
        }.map { it.first }
        if (waiting.isEmpty() || assignOne && ready) return false
        val ui = engine.interfaceEngine
        val batch = hudQueueBatch(action.canQueueMultiple(), ui.isShiftKeyDown(engine), ui.isCtrlKeyDown(engine))
        val nativeLoads = if (assignOne) waiting.associateWith { owner ->
            val factory = owner as FactoryInterface
            if (enhancedProduction) {
                factory.queuedItems?.sumOf { (it as BuildQueueItem).buildCount } ?: 0
            } else {
                factory.getTotalQueuedCount(true)
            }
        } else emptyMap()
        var full = false
        repeat(batch) {
            val recipients = if (assignOne) {
                listOf(waiting.minWith(compareBy({
                    (pending[it]?.size ?: 0) + nativeLoads.getValue(it)
                }, { it.id })))
            } else waiting
            for (owner in recipients) {
                val queue = pending.getOrPut(owner) { ArrayDeque() }
                val id = action.storedActionId
                // A non-repeatable action can occupy at most one future slot on this unit.
                if (!action.canQueueMultiple() && id in queue) continue
                if (queue.size == LIMIT) full = true else queue.addLast(id)
            }
        }
        if (!updating && pending.isNotEmpty()) {
            updating = true
            GameMainDispatcher.addUpdateTask(updateTask)
        }
        if (full) ui.showImportantScreenMessage(readI18n("rwpp.production.pendingFull"))
        ui.activeSpecialAction = null
        if (ready) {
            // A mixed group click issues the affordable portion now. Deferred owners must
            // appear only in their waiting queues, even if their funds change before execution.
            val affordable = candidates.filter { it.second == Admission.Ready }.map { it.first }
            repeat(batch) {
                val command = ui.playerCommandQueued
                affordable.forEach(command::addUnit)
                command.setActionId(action.actionId)
                ui.queueExtraActionOrder(action, null, null, command)
            }
        }
        return true
    }

    fun cancelLast(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine) || pending.isEmpty()) return false
        var removed = false
        for (owner in owners(action, units)) {
            val queue = pending[owner] ?: continue
            val index = queue.indexOfLast { it == action.storedActionId }
            if (index < 0 || index == 0 && inFlight.values.any { owner in it.owners }) continue
            queue.removeAt(index)
            if (queue.isEmpty()) pending.remove(owner)
            removed = true
            if (action.isUnitSpecificAction) break
        }
        return removed
    }

    private fun update(engine: GameEngine) {
        if (!syncSession(engine)) return
        val now = System.nanoTime()
        val controller = engine.commandController
        for (flight in inFlight.values.toList()) {
            val controllable = flight.owners.any { canControl(engine, it) }
            val recheckAt = flight.recheckAt
            if (controllable && recheckAt != null) {
                if (now >= recheckAt) requestNetworkConfirmation(engine, flight, now)
                continue
            }
            if (controllable && (flight.command in controller.localPendingCommands ||
                    flight.command in controller.networkPendingCommands || flight.command in controller.issuedCommands)
            ) continue
            // A command can disappear before submission. Recheck its original request without
            // treating removal from a native transport queue as a successful purchase.
            finishDispatch(flight) { owner, _ -> canControl(engine, owner) }
            nextUpdateAt = now + 100_000_000L
        }
        if (inFlight.isNotEmpty() || now < nextUpdateAt) return
        nextUpdateAt = now + 100_000_000L
        val enhancedProduction = enhanced
        val ready = linkedMapOf<ActionId, ReadyBatch>()
        val factories = pending.entries.iterator()
        scan@ for ((owner, requests) in factories) {
            if (!canControl(engine, owner)) {
                factories.remove()
                continue
            }
            while (requests.isNotEmpty()) {
                val id = requests.first()
                val action = resolveQueueAction(owner, id)
                if (action == null) {
                    requests.removeFirst()
                    continue
                }
                // The displayed item can survive completion or cancellation until the next
                // factory update. Only the authoritative queue determines whether it is idle.
                if (enhancedProduction && !(owner as FactoryInterface).isQueueEmpty) break
                val state = admission(owner, action, useLagHiding = false)
                if (state == Admission.Resources || state == Admission.Waiting) break
                if (state == Admission.Ready) {
                    ready.getOrPut(id) { ReadyBatch(action) }.owners.add(owner)
                    if (!enhancedProduction) break@scan
                    break
                }
                requests.removeFirst()
            }
            if (requests.isEmpty()) factories.remove()
        }
        for ((id, batch) in ready) {
            if (!dispatch(engine, id, batch)) continue
            // Move submitted factories behind the others for the next pass. If owners share
            // resources, the engine charges each accepted purchase in native command order.
            for (owner in batch.owners) pending.remove(owner)?.let { pending[owner] = it }
        }
        if (pending.isEmpty()) stopUpdating()
    }

    private fun dispatch(engine: GameEngine, id: ActionId, batch: ReadyBatch): Boolean {
        val ui = engine.interfaceEngine
        val command = ui.playerCommand
        return try {
            batch.owners.forEach(command::addUnit)
            command.setActionId(batch.action.actionId)
            // This flag survives native serialization and only affects movement waypoints.
            // It distinguishes our deferred command from an otherwise identical ordinary order.
            command.cancelDuplicateWaypoint = true
            ui.queueExtraActionOrder(batch.action, null, null, command)
            inFlight[command.actionId] = Dispatch(batch.owners, id, command)
            true
        } catch (failure: Exception) {
            engine.commandController.localPendingCommands.remove(command)
            engine.commandController.networkPendingCommands.remove(command)
            logger().warn("Could not submit a deferred production order", failure)
            false
        }
    }

    fun recordNetworkSend(command: CommandControllerCommand) {
        val flight = inFlight[command.actionId] ?: return
        if (flight.command !== command) return
        val engine = GameEngine.getInstance() ?: return
        if (engine.networkEngine.isServer || !engine.networkEngine.networked) return
        flight.recheckAt = System.nanoTime() + ECHO_RECHECK_NANOS
    }

    private fun requestNetworkConfirmation(engine: GameEngine, flight: Dispatch, now: Long) {
        flight.recheckAt = now + ECHO_RECHECK_NANOS
        val token = flight.confirmation ?: run {
            val sequence = ++confirmationSequence
            PointF((sequence ushr 16).toFloat(), (sequence and 0xffff).toFloat()).also {
                flight.confirmation = it
            }
        }
        // An action command without units is a native no-op. Its echo passes through the same
        // ordered stream as the real order. A time limit alone cannot distinguish loss from delay.
        val command = engine.interfaceEngine.playerCommand
        command.setActionIdAndTarget(flight.command.actionId, token, null)
        command.cancelDuplicateWaypoint = true
    }

    fun beforeApplication(command: CommandControllerCommand) {
        val flight = inFlight[command.actionId] ?: return
        if (command !== flight.command) {
            val engine = GameEngine.getInstance() ?: return
            if (flight.recheckAt == null || !isLocalDeferredCommand(engine, command)) return
            val target = command.targetPoint
            if (command.unitCount == 0 && target != null) {
                val confirmation = flight.confirmation
                if (confirmation != null && target.x == confirmation.x && target.y == confirmation.y) {
                    // The server skipped the sent order. Clear its local price prediction before retrying.
                    for (owner in flight.owners) {
                        resolveQueueAction(owner, flight.actionId)?.let(owner::dropPendingAction)
                    }
                    finishDispatch(flight) { owner, _ -> canControl(engine, owner) }
                }
                return
            }
            if (target != null || command.unitCount != flight.owners.size ||
                commandOwnerIds(command) != flight.ownerIds
            ) return
        }
        flight.executing = command
        flight.owners.forEachIndexed { index, owner ->
            flight.queuedBefore[index] = (owner as FactoryInterface).getQueuedCountOf(flight.actionId, false)
        }
    }

    fun afterApplication(command: CommandControllerCommand) {
        val flight = inFlight[command.actionId] ?: return
        if (flight.executing !== command) return
        finishDispatch(flight) { owner, index ->
            val accepted = (owner as FactoryInterface).getQueuedCountOf(flight.actionId, false) > flight.queuedBefore[index]
            val action = if (accepted) null else resolveQueueAction(owner, flight.actionId)
            val state = action?.let { admission(owner, it, useLagHiding = false) }
            state == Admission.Resources || state == Admission.Waiting
        }
    }

    private fun isLocalDeferredCommand(engine: GameEngine, command: CommandControllerCommand): Boolean =
        !command.isReplayCommand && command.cancelDuplicateWaypoint && command.team === engine.playerTeam &&
            command.player === engine.playerTeam && !command.removeFromQueue && command.waypoint == null &&
            !command.hasSystemAction && command.targetUnit == null

    private fun commandOwnerIds(command: CommandControllerCommand): Set<Long>? {
        val access = command as? NativeBuildingPlanningCommandAccess ?: return null
        return buildSet(command.unitCount) {
            access.rwppPlanningCommandUnits().forEach { if (it is OrderableUnit) add(it.id) }
            access.rwppPlanningPendingUnitIds().forEach { if (it is Long) add(it) }
        }
    }

    private inline fun finishDispatch(flight: Dispatch, keepWaiting: (OrderableUnit, Int) -> Boolean) {
        inFlight.remove(flight.command.actionId)
        flight.owners.forEachIndexed { index, owner ->
            if (keepWaiting(owner, index)) return@forEachIndexed
            val queue = pending.remove(owner) ?: return@forEachIndexed
            queue.removeFirst()
            if (queue.isNotEmpty()) pending[owner] = queue
        }
        nextUpdateAt = 0L
        InterfaceEngine.markUiDirty()
    }

    private fun stopUpdating() {
        if (updating) {
            GameMainDispatcher.removeUpdateTask(updateTask)
            updating = false
        }
        nextUpdateAt = 0L
    }

    private fun clear() {
        stopUpdating()
        pending.clear()
        inFlight.clear()
        map = null
        team = null
    }

    private fun syncSession(engine: GameEngine): Boolean {
        val currentMap = engine.map
        val currentTeam = engine.playerTeam
        val active = enabled && currentMap != null && currentMap.mapLoaded && currentTeam != null &&
                !engine.replayEngine.isPlayingReplay && !isAeroLiveObserver(engine)
        if (!active || map !== currentMap || team !== currentTeam) {
            clear()
            map = if (active) currentMap else null
            team = if (active) currentTeam else null
        }
        return active
    }

    private fun isProductionAction(action: SpecialAction): Boolean =
        action.actionType == ActionType.none || action.actionType == ActionType.popupQueue

    internal enum class Admission { Ready, Resources, Waiting, Blocked }

    internal data class AdmissionInput(
        val unlocked: Boolean,
        val builtAndNotTransported: Boolean,
        val factorySpeedPositive: Boolean,
        val overTransportCapacity: Boolean,
        val unitCapReached: Boolean,
        val affordable: Boolean,
        val onlyResourceShortage: Boolean,
    ) {
        val productionReady: Boolean get() =
            builtAndNotTransported && factorySpeedPositive && !overTransportCapacity && !unitCapReached
    }

    internal fun classify(input: AdmissionInput): Admission = when {
        !input.unlocked -> Admission.Blocked
        !input.productionReady -> Admission.Waiting
        input.affordable -> Admission.Ready
        input.onlyResourceShortage -> Admission.Resources
        else -> Admission.Blocked
    }

    private fun admission(owner: OrderableUnit, action: SpecialAction, useLagHiding: Boolean = true): Admission = runCatching {
        val team = owner.team ?: return@runCatching Admission.Blocked
        val queueable = action.canQueueMultiple()
        val input = AdmissionInput(
            unlocked = hudActionUnlocked(action, owner),
            builtAndNotTransported = owner.isBuiltAndNotTransported,
            factorySpeedPositive = owner.nanoFactorySpeed > 0f,
            overTransportCapacity = queueable && (owner as FactoryInterface).isOverTransportCapacity,
            unitCapReached = queueable && team.unitCapCount >= team.unitsMax,
            affordable = false,
            onlyResourceShortage = false,
        )
        if (!input.unlocked || !input.productionReady) return@runCatching classify(input)
        val affordable = action.canAffordForUnit(owner, useLagHiding)
        classify(input.copy(affordable = affordable, onlyResourceShortage = !affordable &&
            ProductionPriceProbe.hasOnlyResourceShortage(action, owner, useLagHiding)))
    }.getOrDefault(Admission.Blocked)

    private fun owners(action: SpecialAction, units: List<GameUnit>): List<OrderableUnit> {
        val selection = if (action is IndirectedAction) listOf(action.targetUnit) else units
        return selection.filterIsInstance<OrderableUnit>().filter {
            it is FactoryInterface && !it.dead && !it.removed && resolveQueueAction(it, action.storedActionId) != null
        }
    }

    private fun canControl(engine: GameEngine, owner: OrderableUnit): Boolean =
        !owner.dead && !owner.removed && owner.team != null && engine.interfaceEngine.canPlayerControlUnit(owner)

    private fun resolveQueueAction(owner: OrderableUnit, id: ActionId): SpecialAction? =
        runCatching {
            owner.getSpecialAction(id)?.takeIf { it is QueueUnitAction && isProductionAction(it) && it.getCountForUnit(owner, false) >= 0 }
        }.getOrNull()
}

/** Recheck native action gates with just the resource amounts waived. No resources are mutated. */
internal object ProductionPriceProbe {
    private val checkingOwner = ThreadLocal<GameUnit>()

    fun hasOnlyResourceShortage(action: SpecialAction, owner: OrderableUnit, useLagHiding: Boolean): Boolean {
        checkingOwner.set(owner)
        return try {
            action.canAffordForUnit(owner, useLagHiding)
        } finally {
            checkingOwner.remove()
        }
    }

    fun allows(price: CustomPrice, owner: GameUnit): Boolean? =
        if (checkingOwner.get() === owner) price.checkFlagRequirements(owner) else null
}
