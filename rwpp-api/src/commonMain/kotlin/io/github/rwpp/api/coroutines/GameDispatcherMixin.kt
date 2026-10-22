/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.coroutines

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.api.event.events.FrameBeginEvent
import io.github.rwpp.api.event.events.FrameBeginEventPool
import io.github.rwpp.api.event.events.FrameEndEvent
import io.github.rwpp.api.event.events.FrameEndEventPool
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import io.github.rwpp.api.event.hooks.hasNativeEventListeners
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object GameDispatcherMixin {
    @AsmInject(method = "updateFrameInternal(FI)V", target = InjectionPoint.HEAD, require = 1)
    fun beginNativeFrame(target: Any, deltaTime: Float, deltaMillis: Int) {
        GameMainDispatcher.drainNativeFrame()
        if (hasNativeEventListeners(FrameBeginEvent::class)) {
            val event = FrameBeginEventPool.acquire(target as RWGameEngine, deltaTime, deltaMillis)
            try {
                broadcastNativeEvent(event)
            } finally {
                FrameBeginEventPool.release(event)
            }
        }
    }

    @AsmInject(method = "updateFrameInternal(FI)V", target = InjectionPoint.TAIL, require = 1)
    fun endNativeFrame(target: Any, deltaTime: Float, deltaMillis: Int) {
        if (hasNativeEventListeners(FrameEndEvent::class)) {
            val event = FrameEndEventPool.acquire(target as RWGameEngine, deltaTime, deltaMillis)
            try {
                broadcastNativeEvent(event)
            } finally {
                FrameEndEventPool.release(event)
            }
        }
    }
}
