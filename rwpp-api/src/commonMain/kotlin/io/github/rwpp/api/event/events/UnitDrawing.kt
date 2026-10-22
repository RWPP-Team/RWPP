/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.api.event.AbstractEvent
import io.github.rwpp.api.event.NativeEventPool

/**
 * Runs at the native per-unit draw boundary. Instances dispatched by native hooks are borrowed
 * only for the duration of the broadcast; listeners should copy payload values they need later.
 */
class UnitDrawEvent internal constructor() : AbstractEvent() {
    private var currentUnit: Unit? = null
    private var currentGraphics: GraphicsEngine? = null
    private var currentDeltaTime: Float = 0f

    val unit: Unit
        get() = checkNotNull(currentUnit) { "UnitDrawEvent is no longer being dispatched" }
    val graphics: GraphicsEngine
        get() = checkNotNull(currentGraphics) { "UnitDrawEvent is no longer being dispatched" }
    val deltaTime: Float
        get() = currentDeltaTime

    constructor(unit: Unit, graphics: GraphicsEngine, deltaTime: Float) : this() {
        prepare(unit, graphics, deltaTime)
    }

    internal fun prepare(unit: Unit, graphics: GraphicsEngine, deltaTime: Float) {
        currentUnit = unit
        currentGraphics = graphics
        currentDeltaTime = deltaTime
    }

    internal fun clearForReuse() {
        currentUnit = null
        currentGraphics = null
        currentDeltaTime = 0f
    }
}

/**
 * Runs before a native unit overlay rectangle is drawn. Cancel to suppress the native draw.
 * Native hook instances are borrowed for one completed broadcast and must not be retained.
 */
class UnitOverlayBarEvent internal constructor() : AbstractEvent() {
    private var currentUnit: Unit? = null
    private var currentGraphics: GraphicsEngine? = null
    private var currentRect: RectF? = null
    private var currentPaint: Paint? = null

    val unit: Unit
        get() = checkNotNull(currentUnit) { "UnitOverlayBarEvent is no longer being dispatched" }
    val graphics: GraphicsEngine
        get() = checkNotNull(currentGraphics) { "UnitOverlayBarEvent is no longer being dispatched" }
    val rect: RectF
        get() = checkNotNull(currentRect) { "UnitOverlayBarEvent is no longer being dispatched" }
    val paint: Paint
        get() = checkNotNull(currentPaint) { "UnitOverlayBarEvent is no longer being dispatched" }

    constructor(unit: Unit, graphics: GraphicsEngine, rect: RectF, paint: Paint) : this() {
        prepare(unit, graphics, rect, paint)
    }

    internal fun prepare(unit: Unit, graphics: GraphicsEngine, rect: RectF, paint: Paint) {
        currentUnit = unit
        currentGraphics = graphics
        currentRect = rect
        currentPaint = paint
    }

    internal fun clearForReuse() {
        currentUnit = null
        currentGraphics = null
        currentRect = null
        currentPaint = null
    }

    override val propagateListenerFailures: Boolean
        get() = true
}

internal object UnitDrawEventPool {
    private val pool = NativeEventPool(::UnitDrawEvent) { it.clearForReuse() }

    fun acquire(unit: Unit, graphics: GraphicsEngine, deltaTime: Float): UnitDrawEvent =
        pool.acquire().also { it.prepare(unit, graphics, deltaTime) }

    fun release(event: UnitDrawEvent) = pool.release(event)
}

internal object UnitOverlayBarEventPool {
    private val pool = NativeEventPool(::UnitOverlayBarEvent) { it.clearForReuse() }

    fun acquire(unit: Unit, graphics: GraphicsEngine, rect: RectF, paint: Paint): UnitOverlayBarEvent =
        pool.acquire().also { it.prepare(unit, graphics, rect, paint) }

    fun release(event: UnitOverlayBarEvent) = pool.release(event)
}
