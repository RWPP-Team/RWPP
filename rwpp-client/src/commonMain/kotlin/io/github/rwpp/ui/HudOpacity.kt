/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

/** Opacity value every designed panel alpha was tuned against. */
internal const val DEFAULT_HUD_PANEL_OPACITY = 0.7f

/**
 * Live opacity of HUD panels and their controls. It deliberately does not read the UI object:
 * layout tests compose panels without a Koin container, and touching UI there would fail the
 * whole class initialization instead of falling back to the designed value.
 */
internal object HudOpacity {
    private var state by mutableFloatStateOf(
        runCatching { appKoin.get<Settings>().backgroundTransparency }
            .getOrDefault(DEFAULT_HUD_PANEL_OPACITY)
            .coerceIn(0f, 1f)
    )

    var value: Float
        get() = state
        set(value) {
            state = value.coerceIn(0f, 1f)
        }

    /** Reloads the persisted value, used when the settings screen opens. */
    fun syncFrom(settings: Settings) {
        value = settings.backgroundTransparency
    }
}

/**
 * The setting shifts every HUD panel alpha by the same offset, so the designed look is the
 * default and the slider only makes panels more or less transparent around it.
 */
internal fun hudPanelAlpha(designed: Float): Float =
    (designed + HudOpacity.value - DEFAULT_HUD_PANEL_OPACITY).coerceIn(0.05f, 1f)
