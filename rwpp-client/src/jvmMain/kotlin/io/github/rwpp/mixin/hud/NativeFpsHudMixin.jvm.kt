/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import io.github.rwpp.logic.hud.NativeHudVisibility
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyExpressionValue

/** Desktop draws FPS in these two passes; Android has no corresponding GUI field access. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeFpsHudMixin {
    @ModifyExpressionValue(
        method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(InjectionPoint.FIELD, target = "showFps:Z"), require = 1
    )
    fun worldFps(enabled: Boolean): Boolean = enabled && !NativeHudVisibility.hidden

    @ModifyExpressionValue(
        method = "drawGui(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(InjectionPoint.FIELD, target = "showFps:Z"), require = 1
    )
    fun overlayFps(enabled: Boolean): Boolean = enabled && !NativeHudVisibility.hidden
}
