/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

/** Native drawing owns HUD cadence; display callbacks only cover a stopped/absent game surface. */
internal class NativeHudFrameSchedule {
    @Volatile
    private var lastNativeFrame: Long? = null

    fun onNativeFrame(now: Long) {
        lastNativeFrame = now
    }

    fun needsDisplayFallback(now: Long): Boolean {
        val previous = lastNativeFrame ?: return true
        return now - previous >= 100_000_000L
    }
}
