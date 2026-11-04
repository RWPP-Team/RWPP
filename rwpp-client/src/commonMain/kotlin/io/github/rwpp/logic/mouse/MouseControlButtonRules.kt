/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.mouse

/** Native mouse button ids used by GameEngine.isMouseButtonDown: 1=left, 2=right. */
internal object MouseControlButtonRules {
    /** 1=left orders, 2=right orders; native mouseOrders uses the inverse selection button. */
    fun nativeMouseOrders(orderButton: Int, nativeMouseOrders: Int): Int = when (orderButton) {
        1 -> 2
        2 -> 1
        else -> nativeMouseOrders
    }

    /** Slick mouse callback ids: 0=left, 1=right. Native mode 0 defaults selection to left. */
    fun selectionSlickButton(effectiveMouseOrders: Int): Int =
        if (effectiveMouseOrders == 2) 1 else 0

    /** Native mousePlacement uses 1=left confirmation, 2=right confirmation. */
    fun buildConfirmButton(configuredButton: Int, nativeMousePlacement: Int): Int =
        configuredButton.takeIf { it == 1 || it == 2 } ?: if (nativeMousePlacement == 2) 2 else 1

    /** Per-action values override the build default only for their exact native action type. */
    fun confirmButton(actionType: String?, buildButton: Int, actionButtons: Map<String, Int>): Int? {
        val selected = if (actionType == "placeBuilding") buildButton else actionButtons[actionType]
        return selected?.takeIf { it == 1 || it == 2 }
    }

    fun oppositeNativeButton(button: Int): Int = if (button == 1) 2 else 1
}
