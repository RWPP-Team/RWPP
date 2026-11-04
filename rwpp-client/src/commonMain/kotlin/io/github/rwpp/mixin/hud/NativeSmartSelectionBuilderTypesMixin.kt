/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.selection.SmartSelectionBuilderTypeRules
import io.github.rwpp.logic.selection.countsAsCombatForSmartSelection
import kim.der.asm.api.annotation.*

/**
 * Adds configured builder-like types to the native smart-selection rules at the exact
 * mixed-combat drag-selection checks. It does not alter unit capabilities or other selection paths.
 */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeSmartSelectionBuilderTypesMixin {
    private const val ORDERABLE_CAN_ATTACK =
        "com/corrodinggames/rts/game/units/OrderableUnit.canAttack()Z"
    private const val BASE_IS_BUILDER =
        "com/corrodinggames/rts/game/units/BaseUnit.isBuilder()Z"
    private const val BASE_CAN_ATTACK =
        "com/corrodinggames/rts/game/units/BaseUnit.canAttack()Z"

    /** Native short-circuiting reaches this call only after canAttackIndependently() is true. */
    @WrapOperation(
        method = "handleDragSelection(F)V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = ORDERABLE_CAN_ATTACK),
        require = 1,
    )
    fun configuredBuilderDoesNotStartCombatMode(
        unit: OrderableUnit,
        original: Operation<Boolean>,
    ): Boolean = countsAsCombatForSmartSelection(
        canAttackIndependently = true,
        canAttack = original.call(unit),
        isConfiguredExtraBuilder = SmartSelectionExtraTypes.contains(unit),
    )

    @WrapOperation(
        method = "handleDragSelection(F)V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = BASE_IS_BUILDER),
        require = 1,
    )
    fun configuredTypeIsBuilderForMixedSelection(
        unit: BaseUnit,
        original: Operation<Boolean>,
    ): Boolean = original.call(unit) || SmartSelectionExtraTypes.contains(unit)

    @WrapOperation(
        method = "handleDragSelection(F)V",
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = BASE_CAN_ATTACK),
        require = 1,
    )
    fun configuredTypeIsNonCombatBuilderForMixedSelection(
        unit: BaseUnit,
        original: Operation<Boolean>,
    ): Boolean = original.call(unit) && !SmartSelectionExtraTypes.contains(unit)
}

private object SmartSelectionExtraTypes {
    private var cachedText: String? = null
    private var cachedRules = SmartSelectionBuilderTypeRules.fromConfig("")

    fun contains(unit: BaseUnit): Boolean {
        val settings = runCatching { appKoin.get<Settings>() }.getOrNull() ?: return false
        val currentText = settings.smartSelectionExtraBuilders
        if (currentText != cachedText) {
            cachedText = currentText
            cachedRules = SmartSelectionBuilderTypeRules.fromConfig(currentText)
        }
        val internalName = runCatching { unit.unitTypeInterface?.internalName }.getOrNull().orEmpty()
        return internalName.isNotBlank() && cachedRules.contains(internalName)
    }
}
