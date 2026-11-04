/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import android.graphics.Paint
import android.graphics.RectF
import io.github.rwpp.api.event.hooks.CommandEventCallbacks
import io.github.rwpp.api.event.hooks.UnitDrawEntryCallbacks
import io.github.rwpp.api.event.hooks.UnitOverlayBarCallbacks
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 2000)
object UnitDrawEntryMixin {
    @AsmInject(method = "runOnDestroyHooks(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishUnitDraw(target: Any, deltaTime: Float) = UnitDrawEntryCallbacks.publishUnitDraw(target, deltaTime)
}

@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 1500)
object UnitOverlayBarMixin {
    @JvmStatic
    @Redirect(
        method = "drawOverlay(FZ)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.render.GraphicsEngine.drawRect(Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        ),
        require = 1,
    )
    fun drawNativeOverlayBar(graphics: Any, rect: RectF, paint: Paint) =
        UnitOverlayBarCallbacks.drawNativeOverlayBar(graphics, rect, paint)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object CommandEventMixin {
    @AsmInject(method = "allPathsReady()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishPathsReady(callback: CallbackInfo, target: Any) =
        CommandEventCallbacks.publishPathsReady(target, callback.getReturnValue<Boolean>() == true)
}
