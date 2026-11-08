package io.github.rwpp.ui

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.corrodinggames.rts.gameFramework.GameEngine

internal actual val nativeWorldCanvas: Boolean = false
internal actual fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, draw: DrawScope.() -> Unit,
): AutoCloseable = AutoCloseable { }
internal actual fun drawNativeWorldCanvases(engine: GameEngine) = Unit
