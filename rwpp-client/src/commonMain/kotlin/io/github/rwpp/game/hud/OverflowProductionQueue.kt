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
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.FactoryInterface
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

    private data class Request(val action: ActionId, val owners: List<OrderableUnit>, val singleOwner: Boolean)
    private val pending = ArrayDeque<Request>()
    private var map: NativeMap? = null
    private var team: Team? = null
    private var inFlight: CommandControllerCommand? = null
    private var updating = false
    private val updateTask = Runnable {
        val engine = GameEngine.getInstance() ?: return@Runnable
        synchronized(engine.gameUpdateLock) { update(engine) }
    }

    private val enabled get() = GameVisualSettings.get()?.overflowProductionQueue == true

    fun supports(action: SpecialAction, units: List<GameUnit>): Boolean =
        enabled && isProductionAction(action) && owners(action, units).any()

    fun pendingCount(action: SpecialAction, units: List<GameUnit>): Int {
        if (!enabled || pending.isEmpty() || !isProductionAction(action)) return 0
        val selected = owners(action, units)
        return pending.count { it.action == action.storedActionId && it.owners.any(selected::contains) }
    }

    /** A read-only snapshot of waiting orders for the current selection. Caller holds the game update lock. */
    fun selectedWaitingOrders(engine: GameEngine, selection: List<GameUnit>): Map<String, Int> {
        if (!enabled || map !== engine.map || team !== engine.playerTeam ||
            pending.isEmpty() || selection.isEmpty()
        ) return emptyMap()
        val names = HashMap<Pair<OrderableUnit, ActionId>, String?>()
        val counts = linkedMapOf<String, Int>()
        for (request in pending) {
            for (owner in request.owners) {
                if (owner !in selection || !canControl(engine, owner)) continue
                val name = names.getOrPut(owner to request.action) {
                    val action = resolveQueueAction(owner, request.action)
                    action?.unitType?.displayName
                        ?: action?.getDisplayNameForUnit(owner)?.takeIf { it.isNotBlank() }
                } ?: continue
                counts[name] = (counts[name] ?: 0) + 1
                if (request.singleOwner) break
            }
        }
        return counts
    }

    fun enqueue(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine)) return false
        val owners = owners(action, units).filter { canControl(engine, it) }
        if (owners.isEmpty()) return false
        val ui = engine.interfaceEngine
        val batch = hudQueueBatch(action.canQueueMultiple(), ui.isShiftKeyDown(engine), ui.isCtrlKeyDown(engine))
        val request = Request(action.storedActionId, owners, action.isUnitSpecificAction)
        val accepted = minOf(batch, LIMIT - pending.size)
        repeat(accepted) { pending.addLast(request) }
        if (!updating && pending.isNotEmpty()) {
            updating = true
            GameMainDispatcher.addUpdateTask(updateTask)
        }
        if (accepted < batch) ui.showImportantScreenMessage(readI18n("rwpp.production.pendingFull"))
        ui.activeSpecialAction = null
        return true
    }

    fun cancelLast(engine: GameEngine, action: SpecialAction, units: List<OrderableUnit>): Boolean {
        if (!enabled || !isProductionAction(action) || !syncSession(engine) || pending.isEmpty()) return false
        val owners = owners(action, units)
        val index = pending.indexOfLast { it.action == action.storedActionId && it.owners.any(owners::contains) }
        if (index < 0) return false
        pending.removeAt(index)
        return true
    }

    private fun update(engine: GameEngine) {
        if (!syncSession(engine)) return
        val controller = engine.commandController
        val previous = inFlight
        if (previous != null) {
            if (previous in controller.localPendingCommands ||
                previous in controller.networkPendingCommands || previous in controller.issuedCommands
            ) return
            inFlight = null
        }
        val requests = pending.listIterator(pending.size)
        while (requests.hasPrevious()) {
            val request = requests.previous()
            val candidates = request.owners.mapNotNull { owner ->
                if (!canControl(engine, owner)) return@mapNotNull null
                resolveQueueAction(owner, request.action)?.let { owner to it }
            }
            if (candidates.isEmpty()) {
                requests.remove()
                continue
            }

            // Start at the newest order. Suspended orders keep their position and are skipped.
            // An active order waiting for resources retains priority over older orders.
            val ready = candidates.filter { (owner, action) ->
                owner.isBuiltAndNotTransported && owner.nanoFactorySpeed > 0f &&
                        hudActionUnlocked(action, owner)
            }
            if (ready.isEmpty()) continue
            val (owner, action) = ready.filter { (unit, owned) ->
                runCatching {
                    owned.canAffordForUnit(unit, true) &&
                            (!owned.canQueueMultiple() || unit.team.unitCapCount < unit.team.unitsMax)
                }.getOrDefault(false)
            }.minByOrNull { (unit, owned) ->
                runCatching { owned.getCountForUnit(unit, true) }.getOrDefault(Int.MAX_VALUE)
            } ?: return

            try {
                val ui = engine.interfaceEngine
                val command = ui.playerCommandQueued
                command.addUnit(owner)
                command.setActionId(action.actionId)
                ui.queueExtraActionOrder(action, null, null, command)
                inFlight = command
            } catch (failure: Exception) {
                requests.remove()
                logger().warn("Could not submit a deferred production order", failure)
                return
            }
            if (request.singleOwner || candidates.size == 1) requests.remove()
            else requests.set(request.copy(owners = candidates.map { it.first }.filter { it !== owner }))
            InterfaceEngine.markUiDirty()
            return
        }
        if (pending.isEmpty()) clear()
    }

    private fun clear() {
        if (updating) {
            GameMainDispatcher.removeUpdateTask(updateTask)
            updating = false
        }
        pending.clear()
        inFlight = null
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
            owner.getSpecialAction(id)?.takeIf { isProductionAction(it) && it.getCountForUnit(owner, false) >= 0 }
        }.getOrNull()
}
