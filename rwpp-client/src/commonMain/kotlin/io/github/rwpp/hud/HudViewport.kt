/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

internal data class HudViewportSize(val width: Float, val height: Float)

internal val LocalLegacyHudViewport = staticCompositionLocalOf { HudViewportSize(1280f, 720f) }

/** Shrinks the HUD for compact windows but avoids enlarging controls on larger displays. */
internal fun hudViewportScale(width: Float, height: Float): Float =
    minOf(width / 1280f, height / 720f).coerceIn(0.75f, 1f)

@Composable
internal fun HudViewport(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = hudViewportScale(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            LocalLegacyHudViewport provides HudViewportSize(maxWidth.value, maxHeight.value)
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

internal fun hudRelativePosition(position: Float, viewport: Float, extent: Float): Float =
    if (viewport > extent) (position / (viewport - extent)).coerceIn(0f, 1f) else 0f

internal fun hudAbsolutePosition(fraction: Float, viewport: Float, extent: Float): Float =
    fraction.coerceIn(0f, 1f) * (viewport - extent).coerceAtLeast(0f)
