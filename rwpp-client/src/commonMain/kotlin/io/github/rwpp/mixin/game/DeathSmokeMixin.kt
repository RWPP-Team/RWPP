/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.game

import com.corrodinggames.rts.gameFramework.effects.EffectEmitter
import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

/** Only suppresses the lingering death emitters; unrelated smoke and explosions stay native. */
@AsmMixin("com/corrodinggames/rts/game/units/OrderableUnit")
object DeathSmokeMixin {
    @Redirect(
        method = "spawnDeathEffects(Lcom/corrodinggames/rts/game/units/UnitSize;Z)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEmitter.createSmokeEmitter(FF)Lcom/corrodinggames/rts/gameFramework/effects/EffectEmitter;"
        ), require = 1
    )
    fun smoke(x: Float, y: Float): EffectEmitter? =
        if (GameVisualSettings.get()?.suppressDeathSmoke == true) null else EffectEmitter.createSmokeEmitter(x, y)

    @Redirect(
        method = "spawnDeathEffects(Lcom/corrodinggames/rts/game/units/UnitSize;Z)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEmitter.createFireEmitter(FF)Lcom/corrodinggames/rts/gameFramework/effects/EffectEmitter;"
        ), require = 1
    )
    fun fire(x: Float, y: Float): EffectEmitter? =
        if (GameVisualSettings.get()?.suppressDeathSmoke == true) null else EffectEmitter.createFireEmitter(x, y)
}
