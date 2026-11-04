/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.statusEffects.StatusEffectSpecialActionBlock
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.BattleInterface
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.game.nativeaccess.NativeCommandsAccess
import io.github.rwpp.game.sandbox.sandboxNativeEditorActionsAllowed
import kotlinx.coroutines.launch

/** Use the native command queue so HUD actions retain multiplayer ordering and permission checks. */
internal fun executeHudAction(action: SpecialAction, units: List<Unit>) = executeHudAction(action, units, false)

internal fun executeHudAction(action: SpecialAction, units: List<Unit>, remove: Boolean) {
    GameScope.launch {
        val engine = GameEngine.getInstance()
        val ui = engine.interfaceEngine
        if (units.any { it is com.corrodinggames.rts.game.units.EditorUnit } &&
            !sandboxNativeEditorActionsAllowed(engine)
        ) return@launch

        // UnitTypeInfo changes the current selection from its button callback. It is deliberately
        // available for all selected units (including units the player cannot command), and it
        // must not fall through to any game command handling.
        if (action.actionType == ActionType.infoOnly) {
            InterfaceEngine.markUiDirty()
            action.onButtonClicked(units.firstOrNull(), remove)
            return@launch
        }
        if (engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)) return@launch

        val targets = units.filterIsInstance<OrderableUnit>().filter {
            ui.canPlayerControlUnit(it) && (remove || hudActionUnlocked(action, it))
        }
        val primary = targets.firstOrNull()

        // Target-mode cancellation is local UI state. It must remain possible after selection
        // changes, and cancelActiveAction also clears placement and patrol marker state.
        if (primary == null) {
            val route = hudActionRoute(
                action.actionType, remove, queueEntryAvailable = false,
                activeActionMatches = ui.activeSpecialAction == action
            )
            if (route == HudActionRoute.CancelActiveAction) {
                InterfaceEngine.markUiDirty()
                ui.cancelActiveAction()
            }
            return@launch
        }

        InterfaceEngine.markUiDirty()
        if (action.onButtonClicked(primary, remove)) return@launch

        // onButtonClicked precedes replay handling in the native action bar. Keep that ordering
        // for ordinary actions after preserving UnitTypeInfo's earlier selection-only path.
        if (engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)) return@launch
        val route = hudActionRoute(
            action.actionType, remove,
            queueEntryAvailable = when (action.actionType) {
                ActionType.none, ActionType.popupQueue -> action.getCountForUnit(primary, false) != -1
                else -> false
            },
            activeActionMatches = ui.activeSpecialAction == action
        )
        if (route == HudActionRoute.CancelActiveAction) {
            ui.cancelActiveAction()
            return@launch
        }
        // Native placement remains selectable without funds; the engine validates payment when
        // actually issuing a build. Keep queue/target actions subject to their availability.
        if (!remove && action.actionType != ActionType.placeBuilding &&
            !hudActionAvailable(action) { ui.isSpecialActionAvailable(action, false) }
        ) return@launch

        when (route) {
            HudActionRoute.QueueAction, HudActionRoute.RemoveQueueAction, HudActionRoute.RemoveTargetedOrder -> {
                if (route != HudActionRoute.RemoveTargetedOrder) ui.activeSpecialAction = null
                // The native action bar raises this hint from its own click handling, which the Aero
                // action bar replaces; without this the player queues into a full cap in silence.
                if (route == HudActionRoute.QueueAction && action.canQueueMultiple()) {
                    val team = engine.playerTeam
                    if (team != null && team.unitsMax <= team.unitCapCount) {
                        ui.showImportantScreenMessage(ui.battleInterface.unitCapReachedText)
                    }
                }
                val commandPlan = hudQueueCommandPlan(
                    action.isUnitSpecificAction,
                    remove = route != HudActionRoute.QueueAction
                )
                val batch = if (route == HudActionRoute.RemoveTargetedOrder) 1 else
                    hudQueueBatch(action.canQueueMultiple(), ui.isShiftKeyDown(engine), ui.isCtrlKeyDown(engine))
                repeat(batch) {
                    val command = ui.playerCommandQueued
                    command.removeFromQueue = commandPlan.removeFromQueue
                    if (commandPlan.useUnitSpecificAction) {
                        (ui as NativeCommandsAccess).rwppAddActionUnit(command, action, commandPlan.removeFromQueue)
                    } else {
                        (ui as NativeCommandsAccess).rwppAddActionUnits(command, action)
                    }
                    command.setActionId(action.actionId)
                    if (commandPlan.queueExtraActionOrder) ui.queueExtraActionOrder(action, null, null, command)
                }
            }

            HudActionRoute.PerformDirectAction -> action.performDirectAction(primary)
            HudActionRoute.ActivateAction, HudActionRoute.PlaceBuilding -> {
                ui.specialActionSourceUnit = if (route == HudActionRoute.PlaceBuilding) primary else null
                if (action.actionType == ActionType.patrol) ui.patrolWaypointCount = 0
                if (route == HudActionRoute.PlaceBuilding && ui.activeSpecialAction == null) ui.queuePlacementMode =
                    false
                ui.activeSpecialAction = action
                if (route == HudActionRoute.PlaceBuilding) {
                    ui.placementTapTimer = 0f
                    ui.lastPlacementTapX = -99f
                    ui.lastPlacementTapY = -99f
                    if (!ui.placingBuilding) {
                        ui.placingPointerX = engine.viewCenterWorldX * engine.zoomScale
                        ui.placingPointerY = engine.viewCenterWorldY * engine.zoomScale
                    }
                    ui.placingBuilding = true
                }
            }

            HudActionRoute.Ignore -> kotlin.Unit
        }
    }
}

/** Resolves secondary-button routing before the native command queue is touched. */
internal enum class HudActionRoute {
    QueueAction,
    RemoveQueueAction,
    RemoveTargetedOrder,
    ActivateAction,
    PlaceBuilding,
    CancelActiveAction,
    PerformDirectAction,
    Ignore
}

internal fun hudActionRoute(
    actionType: ActionType,
    remove: Boolean,
    queueEntryAvailable: Boolean,
    activeActionMatches: Boolean
): HudActionRoute {
    if (actionType == ActionType.infoOnly) return HudActionRoute.Ignore
    if (actionType == ActionType.directToAction) return HudActionRoute.PerformDirectAction

    if (!remove) return when (actionType) {
        ActionType.none, ActionType.popupQueue -> HudActionRoute.QueueAction
        ActionType.placeBuilding -> HudActionRoute.PlaceBuilding
        else -> HudActionRoute.ActivateAction
    }

    return when (actionType) {
        ActionType.none, ActionType.popupQueue ->
            if (queueEntryAvailable) HudActionRoute.RemoveQueueAction else HudActionRoute.Ignore

        ActionType.targetGround -> HudActionRoute.RemoveTargetedOrder
        else -> if (activeActionMatches) HudActionRoute.CancelActiveAction else HudActionRoute.Ignore
    }
}

internal data class HudQueueCommandPlan(
    val removeFromQueue: Boolean,
    val useUnitSpecificAction: Boolean,
    val queueExtraActionOrder: Boolean
)

internal fun hudQueueCommandPlan(unitSpecificAction: Boolean, remove: Boolean) =
    HudQueueCommandPlan(
        removeFromQueue = remove, useUnitSpecificAction = unitSpecificAction,
        queueExtraActionOrder = !remove
    )

internal fun hudActionAvailable(action: SpecialAction, nativeAvailability: () -> Boolean): Boolean =
    action.isAvailableForSelection || nativeAvailability()

/** Payment and permission are distinct: placement may wait for money, never for a disabled rule. */
internal fun hudActionUnlocked(action: SpecialAction, unit: OrderableUnit): Boolean =
    ((commonActionType(action.actionType) || action in unit.specialActions)
            && (action.isAvailableForSelection || action.isAvailableForUnit(unit)))
            && !action.isNoCostForUnit(unit)
            && StatusEffectSpecialActionBlock.a(
        unit,
        action.storedActionId
    ) <= 0 &&
            !BattleInterface.isSpecialActionDemoBlocked(action)

internal fun unitHealthRatio(current: Float, maximum: Float): Float =
    if (maximum > 0f && maximum.isFinite() && current.isFinite()) (current / maximum).coerceIn(0f, 1f) else 0f

internal fun commonActionType(actionType: ActionType): Boolean {
    return when (actionType) {
        ActionType.patrol, ActionType.guardUnit -> true
        else -> false
    }
}
