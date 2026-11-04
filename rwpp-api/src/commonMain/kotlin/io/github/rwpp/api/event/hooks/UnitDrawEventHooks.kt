/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.api.event.events.*
import java.util.*
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Per-unit native draw notification. Overlay scope is installed by [UnitOverlayClassPatch]. */
internal object UnitDrawEntryCallbacks {
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
internal object UnitOverlayBarCallbacks {
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

internal object CommandEventCallbacks {
    fun publishPathsReady(target: Any, pathsReady: Boolean) {
        if (pathsReady && hasNativeEventListeners(CommandPathsReadyEvent::class)) {
            broadcastNativeEvent(CommandPathsReadyEvent(target as CommandControllerCommand))
        }
    }
}
