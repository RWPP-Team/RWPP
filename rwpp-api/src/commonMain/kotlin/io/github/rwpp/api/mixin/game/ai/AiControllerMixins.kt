/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game.ai

import io.github.rwpp.api.game.ai.AiControllerRuntime
import io.github.rwpp.api.game.ai.NativeModMutationGate
import kim.der.asm.api.annotation.*

/** Runs custom AI controllers after the native per-team AI update. */
@AsmMixin("com/corrodinggames/rts/game/ai/AI")
object AiControllerHooks {
    @AsmInject(method = "updateResourceTimers(F)V", target = InjectionPoint.TAIL, require = 1)
    fun tickCustomController(target: Any, deltaTime: Float) = AiControllerRuntime.onNativeTick(target, deltaTime)

    @WrapMethod(method = "resetUnitMixes()V", require = 1)
    fun synchronizeUnitMixRebuild(original: Operation<Unit>) =
        NativeModMutationGate.withLock { original.call() }
}

/** Defers AI controller cleanup until native team-slot moves have completed. */
@AsmMixin("com/corrodinggames/rts/game/Team")
object AiTeamLifecycleHooks {
    @AsmInject(method = "removeFromTeamArray()V", target = InjectionPoint.TAIL, require = 1)
    fun teamRemoved(target: Any) = AiControllerRuntime.onTeamRemoval(target)
}
