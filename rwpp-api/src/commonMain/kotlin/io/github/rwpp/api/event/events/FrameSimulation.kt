/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.api.event.AbstractEvent
import io.github.rwpp.api.event.NativeEventPool

/** Runs on the native frame thread after the API dispatcher has been drained. */
class FrameBeginEvent internal constructor() : AbstractEvent() {
    private var currentEngine: RWGameEngine? = null
    private var currentDeltaTime: Float = 0f
    private var currentDeltaMillis: Int = 0

    val engine: RWGameEngine
        get() = checkNotNull(currentEngine) { "FrameBeginEvent is no longer being dispatched" }
    val deltaTime: Float
        get() = currentDeltaTime
    val deltaMillis: Int
        get() = currentDeltaMillis

    constructor(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int) : this() {
        prepare(engine, deltaTime, deltaMillis)
    }

    internal fun prepare(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int) {
        currentEngine = engine
        currentDeltaTime = deltaTime
        currentDeltaMillis = deltaMillis
    }

    internal fun clearForReuse() {
        currentEngine = null
        currentDeltaTime = 0f
        currentDeltaMillis = 0
    }
}

/** Runs after the native frame update returns normally. */
class FrameEndEvent internal constructor() : AbstractEvent() {
    private var currentEngine: RWGameEngine? = null
    private var currentDeltaTime: Float = 0f
    private var currentDeltaMillis: Int = 0

    val engine: RWGameEngine
        get() = checkNotNull(currentEngine) { "FrameEndEvent is no longer being dispatched" }
    val deltaTime: Float
        get() = currentDeltaTime
    val deltaMillis: Int
        get() = currentDeltaMillis

    constructor(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int) : this() {
        prepare(engine, deltaTime, deltaMillis)
    }

    internal fun prepare(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int) {
        currentEngine = engine
        currentDeltaTime = deltaTime
        currentDeltaMillis = deltaMillis
    }

    internal fun clearForReuse() {
        currentEngine = null
        currentDeltaTime = 0f
        currentDeltaMillis = 0
    }
}

/** Runs before native command/state processing for the current simulation tick. */
class SimulationBeforeTickEvent internal constructor() : AbstractEvent() {
    private var currentEngine: RWGameEngine? = null
    private var currentTick: Int = 0

    val engine: RWGameEngine
        get() = checkNotNull(currentEngine) { "SimulationBeforeTickEvent is no longer being dispatched" }
    val tick: Int
        get() = currentTick

    constructor(engine: RWGameEngine, tick: Int) : this() {
        prepare(engine, tick)
    }

    internal fun prepare(engine: RWGameEngine, tick: Int) {
        currentEngine = engine
        currentTick = tick
    }

    internal fun clearForReuse() {
        currentEngine = null
        currentTick = 0
    }
}

/** Runs after a normal native simulation update; `tick` is the native counter at that point. */
class SimulationAfterTickEvent internal constructor() : AbstractEvent() {
    private var currentEngine: RWGameEngine? = null
    private var currentTick: Int = 0

    val engine: RWGameEngine
        get() = checkNotNull(currentEngine) { "SimulationAfterTickEvent is no longer being dispatched" }
    val tick: Int
        get() = currentTick

    constructor(engine: RWGameEngine, tick: Int) : this() {
        prepare(engine, tick)
    }

    internal fun prepare(engine: RWGameEngine, tick: Int) {
        currentEngine = engine
        currentTick = tick
    }

    internal fun clearForReuse() {
        currentEngine = null
        currentTick = 0
    }
}

internal object FrameBeginEventPool {
    private val pool = NativeEventPool(::FrameBeginEvent) { it.clearForReuse() }
    fun acquire(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int): FrameBeginEvent =
        pool.acquire().also { it.prepare(engine, deltaTime, deltaMillis) }

    fun release(event: FrameBeginEvent) = pool.release(event)
}

internal object FrameEndEventPool {
    private val pool = NativeEventPool(::FrameEndEvent) { it.clearForReuse() }
    fun acquire(engine: RWGameEngine, deltaTime: Float, deltaMillis: Int): FrameEndEvent =
        pool.acquire().also { it.prepare(engine, deltaTime, deltaMillis) }

    fun release(event: FrameEndEvent) = pool.release(event)
}

internal object SimulationBeforeTickEventPool {
    private val pool = NativeEventPool(::SimulationBeforeTickEvent) { it.clearForReuse() }
    fun acquire(engine: RWGameEngine, tick: Int): SimulationBeforeTickEvent =
        pool.acquire().also { it.prepare(engine, tick) }

    fun release(event: SimulationBeforeTickEvent) = pool.release(event)
}

internal object SimulationAfterTickEventPool {
    private val pool = NativeEventPool(::SimulationAfterTickEvent) { it.clearForReuse() }
    fun acquire(engine: RWGameEngine, tick: Int): SimulationAfterTickEvent =
        pool.acquire().also { it.prepare(engine, tick) }

    fun release(event: SimulationAfterTickEvent) = pool.release(event)
}
