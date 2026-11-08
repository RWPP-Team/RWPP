package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.corrodinggames.rts.gameFramework.GameEngine

/** World-anchored effects share the game's presentation surface on Android. */
@Composable
internal fun WorldCanvas(modifier: Modifier, onDraw: DrawScope.() -> Unit) {
    if (!nativeWorldCanvas) {
        Canvas(modifier, onDraw)
        return
    }
    val latestDraw = rememberUpdatedState(onDraw)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    DisposableEffect(density, direction) {
        val registration = registerNativeWorldCanvas(density, direction) { latestDraw.value(this) }
        onDispose { registration.close() }
    }
}

internal expect val nativeWorldCanvas: Boolean
internal expect fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, draw: DrawScope.() -> Unit,
): AutoCloseable
internal expect fun drawNativeWorldCanvases(engine: GameEngine)
