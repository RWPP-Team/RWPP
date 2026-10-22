/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasLike
import io.github.rwpp.api.event.events.DrawFrameEventPool
import io.github.rwpp.api.event.events.DrawFrameEvent
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object DrawEventMixin {
    @AsmInject(method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishFrameDraw(target: Any, canvas: CanvasLike?, deltaTime: Float) {
        if (GameEngine.noResources) return
        val engine = target as RWGameEngine
        val graphics = engine.graphics ?: return
        if (!hasNativeEventListeners(DrawFrameEvent::class)) return
        val event = DrawFrameEventPool.acquire(
            engine = engine,
            graphics = graphics,
            canvas = canvas,
            deltaTime = deltaTime,
            frameCount = engine.frameDrawCount,
        )
        try {
            broadcastNativeEvent(event)
        } finally {
            DrawFrameEventPool.release(event)
        }
    }
}
