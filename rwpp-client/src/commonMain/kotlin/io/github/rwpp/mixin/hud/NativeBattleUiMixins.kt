/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.actions.CutSpecialAction
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.ShowSelectedUnitAttackRangeAction
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyReturnValue
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Adds the optional attack-range actions on both native clients. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeAttackRangeActionsMixin {
    @ModifyReturnValue(
        method = "getSpecialActionsForSelection(Lcom/corrodinggames/rts/game/units/Unit;Ljava/util/ArrayList;)Ljava/util/ArrayList;",
        require = 1,
    )
    fun appendAttackRangeActions(
        result: ArrayList<*>?,
        selectedUnit: GameUnit?,
        @Suppress("UNUSED_PARAMETER") selectedUnits: ArrayList<*>?,
    ): ArrayList<*>? {
        if (result == null || !appKoin.get<Settings>().showExtraButton) return result
        @Suppress("UNCHECKED_CAST")
        val actions = result as ArrayList<Any>
        if (selectedUnit == null) {
            actions.addIfMissing(ShowBuildingAttackRangeAction)
            actions.addIfMissing(ShowUnitAttackRangeAction)
        } else if (selectedUnit is com.corrodinggames.rts.game.units.OrderableUnit && selectedUnit.maxAttackRange > 0f) {
            actions.addIfMissing(ShowSelectedUnitAttackRangeAction)
        }
        return actions
    }

    private fun ArrayList<Any>.addIfMissing(action: CutSpecialAction) {
        if (none { it is CutSpecialAction && it.actionIdString == action.actionIdString }) add(action)
    }
}

private object ShowBuildingAttackRangeAction : CutSpecialAction("c_show_attack_range_building") {
    override fun getDisplayName(): String = "Show Attack Range Building"

    override fun getDisplayDescription(): String = readI18n("rwpp.settings.showAttackRange")

    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        appKoin.get<Settings>().showBuildingAttackRange = !appKoin.get<Settings>().showBuildingAttackRange
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}

private object ShowUnitAttackRangeAction : CutSpecialAction("c_show_attack_range_units") {
    override fun getDisplayName(): String = "Show Attack Range Units"

    override fun getDisplayDescription(): String =
        "${readI18n("rwpp.settings.showAttackRange")}\n${appKoin.get<Settings>().showAttackRangeUnit}"

    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        val settings = appKoin.get<Settings>()
        val types = Settings.unitAttackRangeTypes
        val index = types.indexOf(settings.showAttackRangeUnit)
        settings.showAttackRangeUnit = types.getOrNull(index + 1) ?: types.first()
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}

/** Retains native group selection and hotkeys while the Aero HUD draws the visible group row. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeUnitGroupUiMixin {
    @WrapMethod(method = "handleUnitGroups(F)V", require = 1)
    fun updateNativeGroups(deltaTime: Float, operation: Operation<*>) {
        val engine = GameEngine.getInstance()
        val nativeShowGroups = engine.settings.showUnitGroups
        if (nativeShowGroups) engine.settings.showUnitGroups = false
        try {
            operation.call(deltaTime)
        } finally {
            engine.settings.showUnitGroups = nativeShowGroups
        }
    }
}
