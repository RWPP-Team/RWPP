/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.performance

import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyArg

@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object DesktopFrameLimitMixin {
    @ModifyArg(
        method = "updateContainerSettings()V",
        index = 0,
        at = At(value = InjectionPoint.INVOKE, target = "org.newdawn.slick.GameContainer.setTargetFrameRate(I)V"),
        require = 1
    )
    fun frameLimit(native: Int): Int = GameVisualSettings.frameLimit(native)
}
