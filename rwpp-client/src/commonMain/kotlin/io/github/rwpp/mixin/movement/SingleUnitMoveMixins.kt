/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.movement

import io.github.rwpp.game.movement.SingleUnitMoveController
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

/** These are local input hooks; received network commands never pass through them. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object SingleUnitMoveInputMixin {
    @AsmInject(method = "addSelectedUnitsToCommand(Lcom/corrodinggames/rts/gameFramework/CommandControllerCommand;)V",
        target = InjectionPoint.TAIL, require = 1)
    fun afterSelectedUnits(target: Any, command: Any) = SingleUnitMoveController.afterLocalUnitsAdded(target, command)

    @AsmInject(method = "update(F)V", target = InjectionPoint.TAIL, require = 1)
    fun afterLocalUpdate(target: Any) = SingleUnitMoveController.update(target)
}

/** Includes native BUILD and blueprint paths that populate a command without the selection helper. */
@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object SingleUnitMoveCommandMixin {
    @AsmInject(method = "addUnit(Lcom/corrodinggames/rts/game/units/OrderableUnit;)V",
        target = InjectionPoint.TAIL, require = 1)
    fun afterUnitAdded(target: Any) = SingleUnitMoveController.afterLocalUnitAdded(target)

    /** Read-only acknowledgement; received commands retain their original native execution. */
    @AsmInject(method = "issueCommand()V", target = InjectionPoint.TAIL, require = 1)
    fun afterNativeApplication(target: Any) = SingleUnitMoveController.afterNativeCommandApplied(target)
}

@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object SingleUnitMoveNativePathMixin {
    @AsmInject(method = "applyPendingPath(Lcom/corrodinggames/rts/gameFramework/GameEngine;)V",
        target = InjectionPoint.HEAD, require = 1)
    fun beforeApplyPath(target: Any, engine: Any) = SingleUnitMoveController.beforeApplyPath(target, engine)
}
