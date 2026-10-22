/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.api.event.events.SimulationAfterTickEvent
import io.github.rwpp.api.event.events.SimulationAfterTickEventPool
import io.github.rwpp.api.event.events.SimulationBeforeTickEvent
import io.github.rwpp.api.event.events.SimulationBeforeTickEventPool
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object SimulationEventHooks {
    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishBeforeTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val engine = target as RWGameEngine
        if (hasNativeEventListeners(SimulationBeforeTickEvent::class)) {
            val event = SimulationBeforeTickEventPool.acquire(engine, engine.simulationTick)
            try {
                broadcastNativeEvent(event)
            } finally {
                SimulationBeforeTickEventPool.release(event)
            }
        }
    }

    @AsmInject(method = "updateAllGame(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishAfterTick(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val engine = target as RWGameEngine
        if (hasNativeEventListeners(SimulationAfterTickEvent::class)) {
            val event = SimulationAfterTickEventPool.acquire(engine, engine.simulationTick)
            try {
                broadcastNativeEvent(event)
            } finally {
                SimulationAfterTickEventPool.release(event)
            }
        }
    }
}
