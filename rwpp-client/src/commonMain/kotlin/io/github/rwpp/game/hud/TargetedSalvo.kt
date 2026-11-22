package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.custom.actions.ExtraAction
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.logic.GameVisualSettings

internal object TargetedSalvo {
    private var armedAction: SpecialAction? = null

    fun supports(ui: InterfaceEngine, action: SpecialAction, selection: Iterable<Unit>): Boolean {
        if (GameVisualSettings.get()?.showTargetedSalvo != true ||
            action !is ExtraAction || action.actionType != ActionType.targetGround || !action.isUnitSpecificAction
        ) return false
        var owners = 0
        for (unit in selection) {
            if (unit is OrderableUnit && ownedAction(ui, action, unit) != null && ++owners == 2) return true
        }
        return false
    }

    fun arm(action: SpecialAction?) {
        armedAction = action
    }

    fun isActive(ui: InterfaceEngine, action: SpecialAction): Boolean =
        armedAction === action && ui.activeSpecialAction === action &&
            GameVisualSettings.get()?.showTargetedSalvo == true

    fun recipients(ui: InterfaceEngine, action: SpecialAction, x: Float, y: Float): List<OrderableUnit> =
        ui.selectedUnits.filterIsInstance<OrderableUnit>().filter { unit ->
            val owned = ownedAction(ui, action, unit) ?: return@filter false
            hudActionUnlocked(owned, unit) && owned.canAffordForUnit(unit, true) &&
                unit.checkTargetedActionOrder(owned, x, y)
        }

    private fun ownedAction(ui: InterfaceEngine, action: SpecialAction, unit: OrderableUnit): ExtraAction? {
        if (!unit.selected || !ui.canPlayerControlUnit(unit)) return null
        val owned = unit.getSpecialAction(action.storedActionId) as? ExtraAction
        return owned?.takeIf { it.actionType == ActionType.targetGround && it.isAvailableForUnit(unit) }
    }
}
