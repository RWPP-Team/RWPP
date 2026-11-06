/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Theme switches keep the active screen, so the navigator would not replay its transition.
 * This counter is read by the navigator and advanced by the theme host whenever another
 * color scheme becomes active.
 */
internal object AeroThemeTransition {
    var generation by mutableIntStateOf(0)
        private set

    private var lastTheme: String? = null

    fun onThemeChanged(name: String) {
        if (lastTheme == null) {
            // The first reported value is the theme the session starts with.
            lastTheme = name
            return
        }
        if (lastTheme == name) return
        lastTheme = name
        generation++
    }
}
