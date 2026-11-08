package io.github.rwpp.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.CanvasDirect
import java.util.concurrent.CopyOnWriteArrayList

internal actual val nativeWorldCanvas: Boolean = true

internal object AndroidWorldCanvases {
    private class Entry(val density: Density, val direction: LayoutDirection, val draw: DrawScope.() -> Unit) {
        val scope = CanvasDrawScope()
    }
    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(density: Density, direction: LayoutDirection, draw: DrawScope.() -> Unit): AutoCloseable {
        val entry = Entry(density, direction, draw)
        entries.add(entry)
        return AutoCloseable { entries.remove(entry) }
    }

    // Called inside the native world render boundary, before the same Surface buffer is posted.
    // No View invalidation, second vsync, intermediate bitmap, or camera prediction is involved.
    fun draw(engine: GameEngine) {
        if (entries.isEmpty()) return
        val native = (engine.graphics.currentCanvas as? CanvasDirect)?.canvas ?: return
        val checkpoint = native.save()
        try {
            native.clipRect(0f, 0f, engine.gameAreaWidth, engine.screenHeight)
            val canvas = Canvas(native)
            val size = Size(engine.screenWidth, engine.screenHeight)
            for (entry in entries) entry.scope.draw(entry.density, entry.direction, canvas, size, entry.draw)
        } finally {
            native.restoreToCount(checkpoint)
        }
    }
}

internal actual fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, draw: DrawScope.() -> Unit,
): AutoCloseable = AndroidWorldCanvases.register(density, direction, draw)

internal actual fun drawNativeWorldCanvases(engine: GameEngine) = AndroidWorldCanvases.draw(engine)
