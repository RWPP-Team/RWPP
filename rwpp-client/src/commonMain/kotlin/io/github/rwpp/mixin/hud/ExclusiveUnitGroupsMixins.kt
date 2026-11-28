/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.gameFramework.gui.BattleInterface
import com.corrodinggames.rts.gameFramework.gui.UnitHotSelectionGroup
import io.github.rwpp.game.hud.NativeHudGroups
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/UnitHotSelectionGroup")
object ExclusiveUnitGroupAssignmentMixin {
    @AsmInject(method = "addSelectedUnits()V", target = InjectionPoint.RETURN, require = 1)
    fun afterAssignment(target: Any) = NativeHudGroups.afterAssignment(target as UnitHotSelectionGroup)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object ExclusiveUnitGroupsRestoreMixin {
    @AsmInject(
        method = "read(Lcom/corrodinggames/rts/gameFramework/network/InputNetStream;Z)V",
        target = InjectionPoint.TAIL, require = 1,
    )
    fun afterRead(target: Any) = NativeHudGroups.afterGroupsRestored(target as BattleInterface)
}
