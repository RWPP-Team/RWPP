/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import androidx.compose.ui.geometry.Offset
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.hud.AeroDamageSignals
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kotlin.math.max
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** The simulation only enqueues actual HP/shield loss from currently visible units. */
@AsmMixin("com/corrodinggames/rts/game/units/Unit")
object AeroDamageNumbersMixin {
    private data class Before(
        val unit: GameUnit, val health: Float, val position: Offset, val visible: Boolean,
        val world: io.github.rwpp.graphics.NativeWorldFrame?
    )

    private val calls = ThreadLocal.withInitial { ArrayDeque<Before>() }

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.HEAD, require = 1
    )
    fun before(callback: CallbackInfo, self: Any) {
        if (!AeroDamageSignals.enabled) return
        val unit = self as GameUnit
        val engine = GameEngine.getInstance() ?: return
        synchronized(engine.gameUpdateLock) {
            val world = io.github.rwpp.graphics.nativeWorldFrame(engine)
            calls.get().addLast(
                Before(
                    unit, max(0f, unit.hp) + max(0f, unit.shield),
                    Offset(unit.x, unit.y - unit.height - unit.radius),
                    world != null && io.github.rwpp.graphics.nativeUnitVisible(world, unit), world
                )
            )
        }
    }

    @AsmInject(
        method = "damage(Lcom/corrodinggames/rts/game/units/Unit;FLcom/corrodinggames/rts/game/Projectile;)F",
        target = InjectionPoint.RETURN, require = 1
    )
    fun after(callback: CallbackInfo, self: Any) {
        if (!AeroDamageSignals.enabled) {
            calls.remove()
            return
        }
        val stack = calls.get()
        val unit = self as GameUnit
        if (stack.lastOrNull()?.unit !== unit) {
            stack.clear()
            return
        }
        val before = stack.removeLast()
        if (!before.visible) return
        val engine = GameEngine.getInstance() ?: return
        synchronized(engine.gameUpdateLock) {
            val world = io.github.rwpp.graphics.nativeWorldFrame(engine) ?: return@synchronized
            if (before.world?.sameWorld(world) != true) return@synchronized
            val loss = before.health - max(0f, unit.hp) - max(0f, unit.shield)
            AeroDamageSignals.hit(System.identityHashCode(unit), before.position, loss, engine.gameTimeMillis.toLong())
        }
    }
}
