/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.game.units.actions.ActionId
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.IndirectedAction
import com.corrodinggames.rts.game.units.actions.QueueUnitAction
import com.corrodinggames.rts.game.units.actions.SpecialAction
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

    private val pending = linkedMapOf<OrderableUnit, ArrayDeque<ActionId>>()
    private var map: NativeMap? = null
    private var team: Team? = null
    private class Dispatch(val owner: OrderableUnit, val actionId: ActionId, val command: CommandControllerCommand) {
        var executing: CommandControllerCommand? = null
        var queuedBefore = 0
    }
    private data class OrderKey(val ownerId: Long, val actionId: ActionId, val queued: Boolean)

    private var inFlight: Dispatch? = null
    // The server echoes a newly deserialized command. Identical orders from this player return
    // in send order, including any ordinary production orders preceding a deferred one.
    private val sent = HashMap<OrderKey, ArrayDeque<CommandControllerCommand>>()
    private var updating = false
    private var nextUpdateAt = 0L
    private val updateTask = Runnable {
        val engine = GameEngine.getInstance() ?: return@Runnable
        synchronized(engine.gameUpdateLock) { update(engine) }
    }

    private val enabled get() = GameVisualSettings.get()?.overflowProductionQueue == true

    fun supports(action: SpecialAction, units: List<GameUnit>): Boolean =
        enabled && isProductionAction(action) && owners(action, units).any { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@any false
            admission(owner, owned) == Admission.Resources
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

    fun enqueue(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine)) return false
        val candidates = owners(action, units).filter { canControl(engine, it) }.mapNotNull { owner ->
            val owned = resolveQueueAction(owner, action.storedActionId) ?: return@mapNotNull null
            owner to admission(owner, owned)
        }
        val ready = candidates.any { it.second == Admission.Ready }
        val waiting = candidates.filter { it.second == Admission.Resources }.map { it.first }
        if (waiting.isEmpty() || action.isUnitSpecificAction && ready) return false
        val ui = engine.interfaceEngine
        val batch = hudQueueBatch(action.canQueueMultiple(), ui.isShiftKeyDown(engine), ui.isCtrlKeyDown(engine))
        var full = false
        repeat(batch) {
            val recipients = if (action.isUnitSpecificAction) {
                listOf(waiting.minWith(compareBy({
                    (pending[it]?.size ?: 0) + (it as FactoryInterface).getTotalQueuedCount(true)
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
            if (index < 0 || index == 0 && inFlight?.owner === owner) continue
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
        inFlight?.let { flight ->
            val controller = engine.commandController
            val controllable = canControl(engine, flight.owner)
            if (controllable && (flight.command in controller.localPendingCommands ||
                    flight.command in controller.networkPendingCommands || flight.command in controller.issuedCommands ||
                    orderKey(flight.command)?.let { flight.command in sent[it].orEmpty() } == true)
            ) return
            // A command can disappear before submission. Recheck its original request without
            // treating removal from a native transport queue as a successful purchase.
            finishDispatch(flight, keepWaiting = controllable)
            nextUpdateAt = now + 100_000_000L
        }
        if (now < nextUpdateAt) return
        nextUpdateAt = now + 100_000_000L
        val factories = pending.entries.iterator()
        for ((owner, requests) in factories) {
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
                val state = admission(owner, action, useLagHiding = false)
                if (state == Admission.Resources || state == Admission.Waiting) break
                if (state == Admission.Ready && dispatch(engine, owner, id, action)) {
                    // Funds are shared. Wait for the native deduction before picking another
                    // factory, and give the remaining factories priority on the next scan.
                    factories.remove()
                    pending[owner] = requests
                    return
                }
                requests.removeFirst()
            }
            if (requests.isEmpty()) factories.remove()
        }
        if (pending.isEmpty()) stopUpdating()
    }

    private fun dispatch(engine: GameEngine, owner: OrderableUnit, id: ActionId, action: SpecialAction): Boolean {
        val ui = engine.interfaceEngine
        val command = ui.playerCommand
        return try {
            command.addUnit(owner)
            command.setActionId(action.actionId)
            ui.queueExtraActionOrder(action, null, null, command)
            inFlight = Dispatch(owner, id, command)
            true
        } catch (failure: Exception) {
            engine.commandController.localPendingCommands.remove(command)
            engine.commandController.networkPendingCommands.remove(command)
            logger().warn("Could not submit a deferred production order", failure)
            false
        }
    }

    fun recordNetworkSend(command: CommandControllerCommand) {
        if (!enabled) return
        val engine = GameEngine.getInstance() ?: return
        if (engine.networkEngine.isServer || !isLocalProduction(engine, command) || !syncSession(engine)) return
        val access = command as? NativeBuildingPlanningCommandAccess ?: return
        if (access.rwppPlanningCommandUnits().firstOrNull() !is FactoryInterface) return
        val key = orderKey(command) ?: return
        sent.getOrPut(key) { ArrayDeque() }.addLast(command)
    }

    fun beforeApplication(command: CommandControllerCommand) {
        if (inFlight == null && sent.isEmpty()) return
        val engine = GameEngine.getInstance()
        val submitted = if (engine != null && engine.networkEngine.networked && !engine.networkEngine.isServer &&
            isLocalProduction(engine, command)
        ) {
            val key = orderKey(command)
            val queue = sent[key]
            val sentCommand = queue?.removeFirstOrNull()
            if (queue?.isEmpty() == true) sent.remove(key)
            sentCommand
        } else command
        val flight = inFlight ?: return
        if (submitted !== flight.command) return
        flight.executing = command
        flight.queuedBefore = (flight.owner as FactoryInterface).getQueuedCountOf(flight.actionId, false)
    }

    fun afterApplication(command: CommandControllerCommand) {
        val flight = inFlight ?: return
        if (flight.executing !== command) return
        val accepted = (flight.owner as FactoryInterface).getQueuedCountOf(flight.actionId, false) > flight.queuedBefore
        val action = if (accepted) null else resolveQueueAction(flight.owner, flight.actionId)
        val state = action?.let { admission(flight.owner, it, useLagHiding = false) }
        finishDispatch(flight, keepWaiting = state == Admission.Resources || state == Admission.Waiting)
    }

    private fun isLocalProduction(engine: GameEngine, command: CommandControllerCommand): Boolean =
        !command.isReplayCommand && command.team === engine.playerTeam &&
            (command.player == null || command.player === engine.playerTeam) && !command.removeFromQueue &&
            command.waypoint == null && !command.hasSystemAction && command.targetPoint == null &&
            command.targetUnit == null && SpecialAction.isRealActionId(command.actionId)

    private fun orderKey(command: CommandControllerCommand): OrderKey? {
        if (command.unitCount != 1) return null
        val access = command as? NativeBuildingPlanningCommandAccess ?: return null
        val ownerId = (access.rwppPlanningCommandUnits().firstOrNull() as? OrderableUnit)?.id
            ?: access.rwppPlanningPendingUnitIds().firstOrNull() as? Long ?: return null
        return OrderKey(ownerId, command.actionId, command.queueOrder)
    }

    private fun finishDispatch(flight: Dispatch, keepWaiting: Boolean) {
        inFlight = null
        if (!keepWaiting) {
            pending[flight.owner]?.let { queue ->
                queue.removeFirst()
                if (queue.isEmpty()) pending.remove(flight.owner)
            }
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
        inFlight = null
        sent.clear()
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
