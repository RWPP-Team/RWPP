/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwpp.api.event.events.SimulationAfterTickEvent
import io.github.rwpp.api.event.events.SimulationAfterTickEventPool
import io.github.rwpp.api.event.events.SimulationBeforeTickEvent
import io.github.rwpp.api.event.events.SimulationBeforeTickEventPool

internal fun publishNativeBeforeTick(target: Any) {
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

internal fun publishNativeAfterTick(target: Any) {
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
