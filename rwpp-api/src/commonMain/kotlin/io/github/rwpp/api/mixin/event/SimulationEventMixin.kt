/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import io.github.rwpp.api.event.hooks.publishNativeAfterTick
import io.github.rwpp.api.event.hooks.publishNativeBeforeTick
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object SimulationEventMixin {
    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishBeforeTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) =
        publishNativeBeforeTick(target)

    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishAfterTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) =
        publishNativeAfterTick(target)
}
