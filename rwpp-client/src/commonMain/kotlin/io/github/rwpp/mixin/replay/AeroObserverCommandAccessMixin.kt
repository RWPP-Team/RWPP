/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.replay

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.hud.isAeroLiveObserver
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** Changing a display perspective must not grant live command authority. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object AeroObserverCommandAccessMixin {
    @AsmInject(
        method = "canPlayerControlUnit(Lcom/corrodinggames/rts/game/units/Unit;)Z",
        target = InjectionPoint.HEAD, cancellable = true, require = 1
    )
    fun readOnly(callback: CallbackInfo) {
        if (isAeroLiveObserver(GameEngine.getInstance())) callback.setReturnValue(false)
    }
}

/** Native HUD/minimap rendering shares the display perspective, but input uses observer identity. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object AeroObserverHudPerspectiveMixin {
    @kim.der.asm.api.annotation.Redirect(
        method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = kim.der.asm.api.annotation.At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/game/RWGameEngine.drawGui(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V"
        ), require = 1
    )
    fun drawHud(target: Any, canvas: Any?, delta: Float) {
        // Keep the target out of the reflected adapter signature during pre-load mixin validation.
        val engine = target as com.corrodinggames.rts.game.RWGameEngine
        io.github.rwpp.game.replay.withAeroPresentationTeam(engine) {
            engine.drawGui(canvas as? com.corrodinggames.rts.gameFramework.render.CanvasLike, delta)
        }
    }
}
