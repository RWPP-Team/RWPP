/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import io.github.rwpp.graphics.DesktopWorldProjectedShadows
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object WorldProjectedShadowFrameMixin {
    @AsmInject(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        target = InjectionPoint.TAIL,
        require = 1
    )
    fun clearCoveredUnits(callback: CallbackInfo) {
        DesktopWorldProjectedShadows.endDrawGame()
    }
}
