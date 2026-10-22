/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.ai

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

/** Runs custom AI controllers after the native per-team AI update. */
@AsmMixin("com/corrodinggames/rts/game/ai/AI")
object AiControllerHooks {
    @AsmInject(
        method = "updateResourceTimers(F)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun tickCustomController(target: Any, deltaTime: Float) {
        AiControllerRuntime.onNativeTick(target, deltaTime)
    }
}


/** Defers AI controller cleanup until native team-slot moves have completed. */
@AsmMixin("com/corrodinggames/rts/game/Team")
object AiTeamLifecycleHooks {
    @AsmInject(
        method = "removeFromTeamArray()V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun teamRemoved(target: Any) {
        AiControllerRuntime.onTeamRemoval(target)
    }
}