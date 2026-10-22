/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.Unit as GameUnit
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.api.event.events.UnitDrawEventPool
import io.github.rwpp.api.event.events.CommandPathsReadyEvent
import io.github.rwpp.api.event.events.UnitDrawEvent
import io.github.rwpp.api.event.events.UnitOverlayBarEventPool
import io.github.rwpp.api.event.events.UnitOverlayBarEvent
import java.util.ArrayDeque
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

/** Per-unit native draw notification. Overlay scope is installed by [UnitOverlayClassPatch]. */
@AsmMixin("com/corrodinggames/rts/game/units/Unit", priority = 2000)
object UnitDrawEntryMixin {
    @AsmInject(method = "runOnDestroyHooks(F)V", target = InjectionPoint.HEAD, require = 1)
    fun publishUnitDraw(target: Any, deltaTime: Float) {
        if (hasNativeEventListeners(UnitDrawEvent::class)) {
            val event = UnitDrawEventPool.acquire(target as GameUnit, GameEngine.getInstance().graphics, deltaTime)
            try {
                broadcastNativeEvent(event)
            } finally {
                UnitDrawEventPool.release(event)
            }
        }
    }
}

/** Intercepts native overlay rectangles; listeners can draw a replacement and cancel the original. */
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
    fun drawNativeOverlayBar(
        graphics: Any,
        rect: RectF,
        paint: Paint,
    ) {
        val nativeGraphics = graphics as GraphicsEngine
        val unit = UnitOverlayDrawContext.currentOrNull()
        if (unit == null || !hasNativeEventListeners(UnitOverlayBarEvent::class)) {
            nativeGraphics.drawRect(rect, paint)
            return
        }

        val event = UnitOverlayBarEventPool.acquire(unit, nativeGraphics, rect, paint)
        try {
            broadcastNativeEvent(event)
            if (!event.isCancelled) nativeGraphics.drawRect(rect, paint)
        } finally {
            UnitOverlayBarEventPool.release(event)
        }
    }
}

internal object UnitOverlayDrawContext {
    private val frames = ThreadLocal.withInitial { ArrayDeque<GameUnit>() }

    @JvmStatic
    fun shouldTrack(): Boolean = hasNativeEventListeners(UnitOverlayBarEvent::class)

    @JvmStatic
    fun enter(target: Any): Int {
        val stack = frames.get()
        val depth = stack.size
        stack.addLast(target as GameUnit)
        return depth
    }

    fun currentOrNull(): GameUnit? = frames.get().peekLast()

    /** Unwinds nested frames without throwing over a native draw exception. */
    @JvmStatic
    fun restoreDepth(entryDepth: Int) {
        val stack = frames.get()
        while (stack.size > entryDepth) stack.removeLast()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object CommandEventMixin {
    @AsmInject(method = "allPathsReady()Z", target = InjectionPoint.TAIL, require = 1)
    fun publishPathsReady(callback: CallbackInfo, target: Any) {
        if (callback.getReturnValue<Boolean>() == true && hasNativeEventListeners(CommandPathsReadyEvent::class)) {
            broadcastNativeEvent(CommandPathsReadyEvent(target as CommandControllerCommand))
        }
    }
}
