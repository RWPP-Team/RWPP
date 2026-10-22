/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent
import io.github.rwpp.api.event.NativeEventPool
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasLike
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine

/**
 * Native graphics state supplied after the rendered frame completes. Instances dispatched by
 * native hooks are borrowed for one completed broadcast; listeners should copy values they retain.
 */
class DrawFrameEvent internal constructor() : AbstractEvent() {
    private var currentEngine: RWGameEngine? = null
    private var currentGraphics: GraphicsEngine? = null
    private var currentCanvas: CanvasLike? = null
    private var currentDeltaTime: Float = 0f
    private var currentFrameCount: Int = 0

    val engine: RWGameEngine
        get() = checkNotNull(currentEngine) { "DrawFrameEvent is no longer being dispatched" }
    val graphics: GraphicsEngine
        get() = checkNotNull(currentGraphics) { "DrawFrameEvent is no longer being dispatched" }
    val canvas: CanvasLike?
        get() = currentCanvas
    val deltaTime: Float
        get() = currentDeltaTime
    val frameCount: Int
        get() = currentFrameCount

    constructor(
        engine: RWGameEngine,
        graphics: GraphicsEngine,
        canvas: CanvasLike?,
        deltaTime: Float,
        frameCount: Int,
    ) : this() {
        prepare(engine, graphics, canvas, deltaTime, frameCount)
    }

    internal fun prepare(
        engine: RWGameEngine,
        graphics: GraphicsEngine,
        canvas: CanvasLike?,
        deltaTime: Float,
        frameCount: Int,
    ) {
        currentEngine = engine
        currentGraphics = graphics
        currentCanvas = canvas
        currentDeltaTime = deltaTime
        currentFrameCount = frameCount
    }

    internal fun clearForReuse() {
        currentEngine = null
        currentGraphics = null
        currentCanvas = null
        currentDeltaTime = 0f
        currentFrameCount = 0
    }
}

internal object DrawFrameEventPool {
    private val pool = NativeEventPool(::DrawFrameEvent) { it.clearForReuse() }

    fun acquire(
        engine: RWGameEngine,
        graphics: GraphicsEngine,
        canvas: CanvasLike?,
        deltaTime: Float,
        frameCount: Int,
    ): DrawFrameEvent = pool.acquire().also { it.prepare(engine, graphics, canvas, deltaTime, frameCount) }

    fun release(event: DrawFrameEvent) = pool.release(event)
}
