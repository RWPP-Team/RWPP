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
    private var issuedTick = -1

    private val enabled get() = GameVisualSettings.get()?.overflowProductionQueue == true

    fun supports(action: SpecialAction, units: List<GameUnit>): Boolean =
        enabled && isProductionAction(action) && owners(action, units).any()

    fun pendingCount(action: SpecialAction, units: List<GameUnit>): Int {
        if (!enabled || pending.isEmpty() || !isProductionAction(action)) return 0
        val selected = owners(action, units)
        return pending.count { it.action == action.storedActionId && it.owners.any(selected::contains) }
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

    fun update(engine: GameEngine, blocked: Boolean) {
        if (pending.isEmpty() && inFlight == null) return
        if (!syncSession(engine) || blocked) return
        val controller = engine.commandController
        val previous = inFlight
        if (previous != null) {
            if (issuedTick == engine.simulationTick || previous in controller.localPendingCommands ||
                previous in controller.networkPendingCommands || previous in controller.issuedCommands
            ) return
            inFlight = null
        }
        val request = pending.firstOrNull() ?: return
        val candidates = request.owners.mapNotNull { owner ->
            if (!canControl(engine, owner)) return@mapNotNull null
            resolveQueueAction(owner, request.action)?.let { owner to it }
        }
        if (candidates.isEmpty()) {
            pending.removeFirst()
            return
        }

        val affordable = candidates.filter { (owner, action) ->
            runCatching {
                hudActionUnlocked(action, owner) && action.canAffordForUnit(owner, true) &&
                        (!action.canQueueMultiple() || owner.team.unitCapCount < owner.team.unitsMax)
            }.getOrDefault(false)
        }
        val (owner, action) = affordable.minByOrNull { (unit, owned) ->
            runCatching { owned.getCountForUnit(unit, true) }.getOrDefault(Int.MAX_VALUE)
        } ?: return
        try {
            val ui = engine.interfaceEngine
            val command = ui.playerCommandQueued
            command.addUnit(owner)
            command.setActionId(action.actionId)
            ui.queueExtraActionOrder(action, null, null, command)
            issuedTick = engine.simulationTick
            inFlight = command
        } catch (failure: Exception) {
            pending.removeFirst()
            logger().warn("Could not submit a deferred production order", failure)
            return
        }
        pending.removeFirst()
        if (!request.singleOwner && candidates.size > 1) {
            pending.addFirst(request.copy(owners = candidates.map { it.first }.filter { it !== owner }))
        }
        InterfaceEngine.markUiDirty()
    }

    fun clear() {
        pending.clear()
        inFlight = null
        issuedTick = -1
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
