/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.corrodinggames.rts.gameFramework.GameEngine

/** World-anchored effects share the game's presentation surface on Android. */
@Composable
internal fun WorldCanvas(modifier: Modifier, isActive: () -> Boolean = { true }, onDraw: DrawScope.() -> Unit) {
    if (!nativeWorldCanvas) {
        Canvas(modifier, onDraw)
        return
    }
    val latestDraw = rememberUpdatedState(onDraw)
    val latestActive = rememberUpdatedState(isActive)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    DisposableEffect(density, direction) {
        val registration =
            registerNativeWorldCanvas(density, direction, { latestActive.value() }) { latestDraw.value(this) }
        onDispose { registration.close() }
    }
}

internal expect val nativeWorldCanvas: Boolean
internal expect fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, isActive: () -> Boolean, draw: DrawScope.() -> Unit,
): AutoCloseable

internal expect fun drawNativeWorldCanvases(engine: GameEngine)
