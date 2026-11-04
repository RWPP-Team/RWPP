/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Invoker

/** Native fields used to keep custom box gestures out of menus, minimap, and build placement. */
internal interface NativeInterfaceAccess {
    fun rwppCreatePlanningCommand(): CommandControllerCommand
    fun rwppPointerOnMinimap(): Boolean
    fun rwppPointerDownOverSidebar(): Boolean
    fun rwppPointerOverUiElement(): Boolean
    fun rwppActiveSpecialAction(): SpecialAction?
    fun rwppTapReleased(): Boolean
    fun rwppPointerDown(): Boolean
    fun rwppPointerDragging(): Boolean
    fun rwppPointerInSidebar(): Boolean
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
@AddInterface("io/github/rwpp/mixin/hud/NativeInterfaceAccess")
abstract class NativeInterfaceAccessMixin {
    @Invoker("getPlayerCommand")
    abstract fun rwppCreatePlanningCommand(): CommandControllerCommand

    @Accessor("pointerOnMinimap")
    abstract fun rwppPointerOnMinimap(): Boolean

    @Accessor("pointerDownOverSidebar")
    abstract fun rwppPointerDownOverSidebar(): Boolean

    @Accessor("pointerOverUiElement")
    abstract fun rwppPointerOverUiElement(): Boolean

    @Accessor("activeSpecialAction")
    abstract fun rwppActiveSpecialAction(): SpecialAction?
    @Accessor("tapReleased")
    abstract fun rwppTapReleased(): Boolean
    @Accessor("pointerDown")
    abstract fun rwppPointerDown(): Boolean
    @Accessor("pointerDragging")
    abstract fun rwppPointerDragging(): Boolean
    @Accessor("pointerInSidebar")
    abstract fun rwppPointerInSidebar(): Boolean
}