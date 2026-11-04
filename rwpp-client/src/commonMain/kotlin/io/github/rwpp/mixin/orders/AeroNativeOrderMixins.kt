/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.orders

import android.graphics.Point
import com.corrodinggames.rts.gameFramework.effects.EffectEngine
import com.corrodinggames.rts.gameFramework.effects.EffectObject
import com.corrodinggames.rts.gameFramework.effects.EffectType
import com.corrodinggames.rts.gameFramework.effects.Priority
import io.github.rwpp.game.graphics.AeroOrderCapture
import kim.der.asm.api.annotation.*
import com.corrodinggames.rts.game.units.Unit as NativeUnit

// Rewrite spawn calls before WrapMethod moves the original command body to its continuation.
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine", priority = 1100)
object AeroNativeOrderEffectCaptureMixin {
    @WrapOperation(
        method = "orderMove(FFLandroid/graphics/Point;)V", at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEngine.spawnEffectMarked(FFFLcom/corrodinggames/rts/gameFramework/effects/EffectType;ZLcom/corrodinggames/rts/gameFramework/effects/Priority;)Lcom/corrodinggames/rts/gameFramework/effects/EffectObject;"
        ), require = 1
    )
    fun moveEffect(
        engine: EffectEngine, x: Float, y: Float, height: Float, type: EffectType, forced: Boolean,
        priority: Priority, original: Operation<EffectObject?>
    ): EffectObject? =
        original.call(engine, x, y, height, type, forced, priority).also(AeroOrderCapture::record)

    @WrapOperation(
        method = "orderAttackMove(FFLandroid/graphics/Point;)V", at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEngine.spawnEffectMarked(FFFLcom/corrodinggames/rts/gameFramework/effects/EffectType;ZLcom/corrodinggames/rts/gameFramework/effects/Priority;)Lcom/corrodinggames/rts/gameFramework/effects/EffectObject;"
        ), require = 1
    )
    fun attackMoveEffect(
        engine: EffectEngine, x: Float, y: Float, height: Float, type: EffectType, forced: Boolean,
        priority: Priority, original: Operation<EffectObject?>
    ): EffectObject? =
        original.call(engine, x, y, height, type, forced, priority).also(AeroOrderCapture::record)

    @WrapOperation(
        method = "orderPatrolWaypoint(FFLandroid/graphics/Point;Z)V", at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEngine.spawnEffectMarked(FFFLcom/corrodinggames/rts/gameFramework/effects/EffectType;ZLcom/corrodinggames/rts/gameFramework/effects/Priority;)Lcom/corrodinggames/rts/gameFramework/effects/EffectObject;"
        ), require = 1
    )
    fun patrolEffect(
        engine: EffectEngine, x: Float, y: Float, height: Float, type: EffectType, forced: Boolean,
        priority: Priority, original: Operation<EffectObject?>
    ): EffectObject? =
        original.call(engine, x, y, height, type, forced, priority).also(AeroOrderCapture::record)

    @WrapOperation(
        method = "orderAttack(Lcom/corrodinggames/rts/game/units/Unit;)V", at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/effects/EffectEngine.spawnEffectMarked(FFFLcom/corrodinggames/rts/gameFramework/effects/EffectType;ZLcom/corrodinggames/rts/gameFramework/effects/Priority;)Lcom/corrodinggames/rts/gameFramework/effects/EffectObject;"
        ), require = 1
    )
    fun attackEffect(
        engine: EffectEngine, x: Float, y: Float, height: Float, type: EffectType, forced: Boolean,
        priority: Priority, original: Operation<EffectObject?>
    ): EffectObject? =
        original.call(engine, x, y, height, type, forced, priority).also(AeroOrderCapture::record)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object AeroNativeOrderClickMixin {
    @WrapMethod(method = "orderMove(FFLandroid/graphics/Point;)V", require = 1)
    fun move(x: Float, y: Float, point: Point?, original: Operation<kotlin.Unit>) {
        AeroOrderCapture.run(x, y, false) { original.call(x, y, point) }
    }

    @WrapMethod(method = "orderAttackMove(FFLandroid/graphics/Point;)V", require = 1)
    fun attackMove(x: Float, y: Float, point: Point?, original: Operation<kotlin.Unit>) {
        AeroOrderCapture.run(x, y, true) { original.call(x, y, point) }
    }

    @WrapMethod(method = "orderPatrolWaypoint(FFLandroid/graphics/Point;Z)V", require = 1)
    fun patrol(x: Float, y: Float, point: Point?, append: Boolean, original: Operation<kotlin.Unit>) {
        AeroOrderCapture.run(x, y, false) { original.call(x, y, point, append) }
    }

    @WrapMethod(method = "orderAttack(Lcom/corrodinggames/rts/game/units/Unit;)V", require = 1)
    fun attack(target: NativeUnit, original: Operation<kotlin.Unit>) {
        AeroOrderCapture.run(target.x, target.y - target.height, true) { original.call(target) }
    }
}
