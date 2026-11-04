/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.mouse.MouseControlButtonRules
import io.github.rwpp.mixin.hud.NativeInterfaceAccess

/** Reads the persisted mouse choices at use time; native settings remain unchanged on disk. */
internal object MouseControlPreferencesController {
    fun nativeMouseOrders(nativeValue: Int): Int = MouseControlButtonRules.nativeMouseOrders(
        if (runCatching { GameEngine.isDesktopVersion() && GameEngine.getInstance().settings.mouseSupport }
                .getOrDefault(false)
        ) runCatching { appKoin.get<Settings>().mouseOrderButton }.getOrDefault(0) else 0,
        nativeValue,
    )

    fun selectionSlickButton(nativeMouseOrders: Int): Int = MouseControlButtonRules.selectionSlickButton(
        nativeMouseOrders(
            runCatching { GameEngine.getInstance().settings.mouseOrders }.getOrDefault(nativeMouseOrders),
        ),
    )

    fun planningSlickButton(nativeMouseOrders: Int, nativeMousePlacement: Int, blueprintPlacement: Boolean): Int =
        if (blueprintPlacement) {
            val button = confirmButton(ActionType.placeBuilding.name)
                ?: MouseControlButtonRules.buildConfirmButton(0, nativeMousePlacement)
            button - 1
        } else {
            selectionSlickButton(nativeMouseOrders)
        }

    fun confirmButton(actionType: String?): Int? = MouseControlButtonRules.confirmButton(
        actionType,
        runCatching { appKoin.get<Settings>().mouseBuildConfirmButton }.getOrDefault(0),
        runCatching { appKoin.get<Settings>().actionConfirmButtons }.getOrDefault(emptyMap()),
    )

    fun placementButtonPressedOverride(): Boolean? {
        val access = GameEngine.getInstance().interfaceEngine as? NativeInterfaceAccess ?: return null
        val button = confirmButton(access.rwppActiveSpecialAction()?.actionType?.name) ?: return null
        val game = runCatching { GameEngine.getInstance() }.getOrNull() ?: return null
        return !(!GameEngine.isDesktopVersion() || !game.settings.mouseSupport ||
                (!access.rwppTapReleased() && !access.rwppPointerDown())) && game.isMouseButtonDown(button)
    }

    fun placementCancelPressedOverride(): Boolean? {
        val access = GameEngine.getInstance().interfaceEngine as? NativeInterfaceAccess ?: return null
        val button = confirmButton(access.rwppActiveSpecialAction()?.actionType?.name) ?: return null
        val game = runCatching { GameEngine.getInstance() }.getOrNull() ?: return null
        if (!GameEngine.isDesktopOrMac() || !game.settings.mouseSupport || !access.rwppTapReleased() ||
            access.rwppPointerDragging() || access.rwppPointerInSidebar()
        ) return false
        return game.isMouseButtonDown(MouseControlButtonRules.oppositeNativeButton(button))
    }
}
