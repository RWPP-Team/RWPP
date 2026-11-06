/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal actual fun isHudSecondaryPress(event: PointerEvent): Boolean =
    event.button == PointerButton.Secondary || event.buttons.isSecondaryPressed

internal object HudFrameClock {
    private val callbacks = CopyOnWriteArrayList<Runnable>()
    fun register(callback: Runnable): AutoCloseable {
        callbacks.add(callback)
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun tick() {
        val engine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance()
        if (engine == null) callbacks.forEach { it.run() }
        else synchronized(engine.gameUpdateLock) { callbacks.forEach { it.run() } }
    }
}

internal actual fun registerHudFrame(callback: Runnable): AutoCloseable = HudFrameClock.register(callback)

/** The desktop frame callback already runs on the game thread that samples the engine. */
internal actual fun flushPendingHudFrame() = Unit

internal actual fun requestWorldPresentationFrame() {
    DesktopComposeRuntime.requestFrame()
}
