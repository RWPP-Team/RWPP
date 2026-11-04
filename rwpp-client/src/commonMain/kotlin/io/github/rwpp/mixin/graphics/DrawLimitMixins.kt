/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.RectF
import io.github.rwpp.graphics.DrawLimitPolicy
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

@AsmMixin("com/corrodinggames/rts/game/units/Unit")
object UnitDrawLimitMixin {
    @Redirect(
        method = "shouldRender(Lcom/corrodinggames/rts/gameFramework/GameEngine;)Z",
        at = At(value = InjectionPoint.INVOKE, target = "android.graphics.RectF.contains(FF)Z"), require = 1
    )
    fun visible(rect: RectF, x: Float, y: Float): Boolean = DrawLimitPolicy.contains(rect, x, y)
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnit")
object CustomUnitDrawLimitMixin {
    @Redirect(
        method = "shouldRender(Lcom/corrodinggames/rts/gameFramework/GameEngine;)Z",
        at = At(value = InjectionPoint.INVOKE, target = "android.graphics.RectF.contains(FF)Z"), require = 1
    )
    fun visible(rect: RectF, x: Float, y: Float): Boolean = DrawLimitPolicy.contains(rect, x, y)
}

@AsmMixin("com/corrodinggames/rts/game/Projectile")
object ProjectileDrawLimitMixin {
    @Redirect(
        method = "shouldRender(Lcom/corrodinggames/rts/gameFramework/GameEngine;)Z",
        at = At(value = InjectionPoint.INVOKE, target = "android.graphics.RectF.contains(FF)Z"), require = 1
    )
    fun visible(rect: RectF, x: Float, y: Float): Boolean = DrawLimitPolicy.contains(rect, x, y)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/effects/Blueprint")
object BlueprintDrawLimitMixin {
    @Redirect(
        method = "draw(F)V",
        at = At(value = InjectionPoint.INVOKE, target = "android.graphics.RectF.contains(FF)Z"), require = 1
    )
    fun visible(rect: RectF, x: Float, y: Float): Boolean = DrawLimitPolicy.contains(rect, x, y)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/effects/EffectEngine")
object EffectDrawLimitMixin {
    @Redirect(
        method = "spawnEffect(FFFLcom/corrodinggames/rts/gameFramework/effects/EffectType;ZLcom/corrodinggames/rts/gameFramework/effects/Priority;)Lcom/corrodinggames/rts/gameFramework/effects/EffectObject;",
        at = At(value = InjectionPoint.INVOKE, target = "android.graphics.RectF.contains(FF)Z"), require = 1
    )
    fun visible(rect: RectF, x: Float, y: Float): Boolean = DrawLimitPolicy.contains(rect, x, y)
}
