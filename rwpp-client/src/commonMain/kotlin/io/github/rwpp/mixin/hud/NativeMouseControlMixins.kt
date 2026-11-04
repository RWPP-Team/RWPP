/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import io.github.rwpp.game.hotkeys.MouseControlPreferencesController
import kim.der.asm.api.annotation.*


/** Virtualizes only native mouse-order reads and the placement confirmation predicates. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object NativeMouseOrderButtonsMixin {
    @ModifyExpressionValue(
        method = "update(F)V",
        at = At(InjectionPoint.FIELD, target = "mouseOrders:I"),
        require = 2,
    )
    fun updateMouseOrders(value: Int): Int = MouseControlPreferencesController.nativeMouseOrders(value)

    @ModifyExpressionValue(
        method = "handlePointerEvents(F)V",
        at = At(InjectionPoint.FIELD, target = "mouseOrders:I"),
        require = 2,
    )
    fun pointerMouseOrders(value: Int): Int = MouseControlPreferencesController.nativeMouseOrders(value)

    @ModifyReturnValue(method = "isPlacementButtonPressed()Z", require = 1)
    fun placementButtonPressed(value: Boolean): Boolean =
        MouseControlPreferencesController.placementButtonPressedOverride() ?: value

    @ModifyReturnValue(method = "isPlacementCancelButtonPressed()Z", require = 1)
    fun placementButtonCancelled(value: Boolean): Boolean =
        MouseControlPreferencesController.placementCancelPressedOverride() ?: value
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object NativeMouseSelectionButtonMixin {
    @ModifyExpressionValue(
        method = "handleDragSelection(F)V",
        at = At(InjectionPoint.FIELD, target = "mouseOrders:I"),
        require = 1,
    )
    fun dragSelectionMouseOrders(value: Int): Int = MouseControlPreferencesController.nativeMouseOrders(value)
}
