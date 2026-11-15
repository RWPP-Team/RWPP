/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Absolute panel alpha override. `null` follows the HUD slider, as battlefield panels should;
 * any value replaces the designed alpha outright. Modal dialogs publish `1f`: a translucent
 * alert lets terrain and native briefing text show through its own message.
 */
internal val LocalPanelAlphaOverride = staticCompositionLocalOf<Float?> { null }

/** Slider offset from the designed look; callable from draw scopes, which cannot read locals. */
internal fun hudPanelAlpha(designed: Float): Float =
    (designed + HudOpacity.value - DEFAULT_HUD_PANEL_OPACITY).coerceIn(0.05f, 1f)

/**
 * Same shift for composables, but a surrounding [LocalPanelAlphaOverride] wins. The setting exists
 * for surfaces over terrain; a modal must stay readable whatever the slider says.
 */
@Composable
internal fun scopedPanelAlpha(designed: Float): Float {
    val override = LocalPanelAlphaOverride.current
    return override?.coerceIn(0.05f, 1f) ?: hudPanelAlpha(designed)
}
