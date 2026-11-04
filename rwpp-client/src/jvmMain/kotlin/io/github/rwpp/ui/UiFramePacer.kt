/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import java.util.concurrent.atomic.AtomicBoolean

/** Pace expensive UI work without sleeping or limiting native game frames. */
internal class UiFramePacer {
    private val requested = AtomicBoolean(true)
    private var nextFrame = 0L
    private var previousRate = 0

    fun request() {
        requested.set(true)
    }

    fun shouldRender(now: Long, refreshRate: Int): Boolean {
        val rate = refreshRate.coerceIn(30, 300)
        val immediate = requested.getAndSet(false) || previousRate != rate
        previousRate = rate
        if (!immediate && now < nextFrame) return false
        val period = 1_000_000_000L / rate
        nextFrame = if (immediate || now - nextFrame > period) now + period else nextFrame + period
        return true
    }
}
