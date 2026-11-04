/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwpp.graphics.NativeObjectOpacity
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeObjectOpacityMixin {
    @Redirect(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/GameObject.draw(F)Z"
        ), require = 1
    )
    fun draw(obj: GameObject, delta: Float): Boolean = NativeObjectOpacity.drawObject(obj, delta)

    @Redirect(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/GameObject.PENDING_d_float(F)V"
        ), require = 1
    )
    fun back(obj: GameObject, delta: Float) = NativeObjectOpacity.drawBack(obj, delta)

    @Redirect(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/GameObject.drawMinimap(F)Z"
        ), require = 1
    )
    fun icon(obj: GameObject, delta: Float): Boolean = NativeObjectOpacity.drawWorldIcon(obj, delta)
}
