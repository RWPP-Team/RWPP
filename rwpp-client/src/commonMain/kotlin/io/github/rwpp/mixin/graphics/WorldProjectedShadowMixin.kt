/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import com.corrodinggames.rts.game.units.OrderableUnit
import io.github.rwpp.graphics.replacesNativeProjectedShadow
import io.github.rwpp.logic.GameVisualSettings

@kim.der.asm.api.annotation.AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object WorldProjectedShadowMixin {
    @kim.der.asm.api.annotation.AsmInject(
        method = "drawTurretImage()Z", target = kim.der.asm.api.annotation.InjectionPoint.HEAD,
        cancellable = true, require = 1
    )
    fun shadow(callback: kim.der.asm.api.annotation.CallbackInfo, self: Any) {
        if (GameVisualSettings.get()?.drawUnitShadows == false) callback.setReturnValue(false)
        else if (replacesNativeProjectedShadow(self as OrderableUnit)) callback.setReturnValue(true)
    }
}
