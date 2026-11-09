/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

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
    private class Entry(
        val density: Density, val direction: LayoutDirection,
        val isActive: () -> Boolean, val draw: DrawScope.() -> Unit
    ) {
        val scope = CanvasDrawScope()
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(
        density: Density,
        direction: LayoutDirection,
        isActive: () -> Boolean,
        draw: DrawScope.() -> Unit
    ): AutoCloseable {
        val entry = Entry(density, direction, isActive, draw)
        entries.add(entry)
        return AutoCloseable { entries.remove(entry) }
    }

    // Called inside the native world render boundary, before the same Surface buffer is posted.
    // No View invalidation, second vsync, intermediate bitmap, or camera prediction is involved.
    fun draw(engine: GameEngine) {
        if (entries.none { active(it) }) return
        val native = (engine.graphics.currentCanvas as? CanvasDirect)?.canvas ?: return
        val checkpoint = native.save()
        try {
            native.clipRect(0f, 0f, engine.gameAreaWidth, engine.screenHeight)
            val canvas = Canvas(native)
            val size = Size(engine.screenWidth, engine.screenHeight)
            for (entry in entries) if (active(entry)) {
                try {
                    entry.scope.draw(entry.density, entry.direction, canvas, size, entry.draw)
                } catch (failure: Throwable) {
                    entries.remove(entry)
                    warnHudFailure("world HUD draw", failure)
                }
            }
        } finally {
            native.restoreToCount(checkpoint)
        }
    }

    private fun active(entry: Entry): Boolean = try {
        entry.isActive()
    } catch (failure: Throwable) {
        entries.remove(entry)
        warnHudFailure("world HUD state", failure)
        false
    }
}

internal actual fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, isActive: () -> Boolean, draw: DrawScope.() -> Unit,
): AutoCloseable = AndroidWorldCanvases.register(density, direction, isActive, draw)

internal actual fun drawNativeWorldCanvases(engine: GameEngine) = AndroidWorldCanvases.draw(engine)
