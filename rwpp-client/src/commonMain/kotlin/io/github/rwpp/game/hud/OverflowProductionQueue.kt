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

/** Local intent only. Each accepted order is sent through the ordinary native command controller. */
internal object OverflowProductionQueue {
    private const val LIMIT = 256

    private val pending = linkedMapOf<OrderableUnit, ArrayDeque<ActionId>>()
    private var map: NativeMap? = null
    private var team: Team? = null
    private val inFlight = HashMap<OrderableUnit, CommandControllerCommand>()
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
                listOf(waiting.minWith(compareBy({ pending[it]?.size ?: 0 }, { (it as FactoryInterface).getTotalQueuedCount(true) }, { it.id })))
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
        if (!ready) ui.activeSpecialAction = null
        return !ready
    }

    fun cancelLast(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine) || pending.isEmpty()) return false
        var removed = false
        for (owner in owners(action, units)) {
            val queue = pending[owner] ?: continue
            val index = queue.indexOfLast { it == action.storedActionId }
            if (index < 0) continue
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
        if (now < nextUpdateAt) return
        nextUpdateAt = now + 100_000_000L
        val controller = engine.commandController
        inFlight.values.removeAll {
            it !in controller.localPendingCommands && it !in controller.networkPendingCommands && it !in controller.issuedCommands
        }
        val factories = pending.iterator()
        while (factories.hasNext()) {
            val (owner, requests) = factories.next()
            if (!canControl(engine, owner)) {
                factories.remove()
                continue
            }
            val queue = requests.iterator()
            var first = true
            while (queue.hasNext()) {
                val action = resolveQueueAction(owner, queue.next())
                val state = if (action == null) Admission.Blocked else admission(owner, action)
                if (state == Admission.Blocked) {
                    queue.remove()
                    continue
                }
                if (first && state == Admission.Ready && action != null && owner !in inFlight) {
                    try {
                        val ui = engine.interfaceEngine
                        val command = ui.playerCommandQueued
                        command.addUnit(owner)
                        command.setActionId(action.actionId)
                        ui.queueExtraActionOrder(action, null, null, command)
                        inFlight[owner] = command
                        InterfaceEngine.markUiDirty()
                    } catch (failure: Exception) {
                        logger().warn("Could not submit a deferred production order", failure)
                    }
                    queue.remove()
                }
                first = false
            }
            if (requests.isEmpty()) factories.remove()
        }
        if (pending.isEmpty()) clear()
    }

    private fun clear() {
        if (updating) {
            GameMainDispatcher.removeUpdateTask(updateTask)
            updating = false
        }
        pending.clear()
        inFlight.clear()
        nextUpdateAt = 0L
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

    private enum class Admission { Ready, Resources, Blocked }

    private fun admission(owner: OrderableUnit, action: SpecialAction): Admission = runCatching {
        if (!owner.isBuiltAndNotTransported || owner.nanoFactorySpeed <= 0f ||
            !hudActionUnlocked(action, owner) ||
            action.canQueueMultiple() && (owner.team.unitCapCount >= owner.team.unitsMax ||
                (owner as FactoryInterface).isOverTransportCapacity)
        ) return@runCatching Admission.Blocked
        if (action.canAffordForUnit(owner, true)) Admission.Ready
        else if (ProductionPriceProbe.hasOnlyResourceShortage(action, owner)) Admission.Resources
        else Admission.Blocked
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

    fun hasOnlyResourceShortage(action: SpecialAction, owner: OrderableUnit): Boolean {
        checkingOwner.set(owner)
        return try {
            action.canAffordForUnit(owner, true)
        } finally {
            checkingOwner.remove()
        }
    }

    fun allows(price: CustomPrice, owner: GameUnit): Boolean? =
        if (checkingOwner.get() === owner) price.checkFlagRequirements(owner) else null
}
