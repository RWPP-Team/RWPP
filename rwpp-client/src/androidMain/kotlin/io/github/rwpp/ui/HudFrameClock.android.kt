/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import io.github.rwpp.api.coroutines.GameMainDispatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal actual fun isHudSecondaryPress(event: PointerEvent): Boolean = event.buttons.isSecondaryPressed

/**
 * Use one display-frame callback for all Compose game callbacks, then hand one coalesced batch to
 * the dispatcher owner. This keeps live engine objects on their native owner while ensuring the
 * Android main looper never waits for gameUpdateLock on every HUD component's frame callback.
 */
internal object HudFrameClock {
    private val callbacks = CopyOnWriteArrayList<Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private val frameQueued = AtomicBoolean(false)
    private var framePosted = false // Choreographer scheduling is confined to the main looper.

    private val gameFrame = Runnable {
        try {
            callbacks.forEach { callback ->
                try {
                    callback.run()
                } catch (failure: Throwable) {
                    Log.e(TAG, "Error running a HUD frame callback", failure)
                }
            }
        } finally {
            frameQueued.set(false)
        }
    }

    private val displayFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (callbacks.isEmpty()) return

            if (frameQueued.compareAndSet(false, true)) {
                try {
                    GameMainDispatcher.dispatch(EmptyCoroutineContext, gameFrame)
                } catch (failure: Throwable) {
                    frameQueued.set(false)
                    Log.e(TAG, "Could not enqueue a HUD frame", failure)
                }
            }
            ensureFramePosted()
        }
    }

    fun register(callback: Runnable): AutoCloseable {
        val active = AtomicBoolean(true)
        val guarded = Runnable { if (active.get()) callback.run() }
        callbacks.add(guarded)
        handler.post { ensureFramePosted() }
        return AutoCloseable {
            active.set(false)
            callbacks.remove(guarded)
            handler.post {
                if (callbacks.isEmpty() && framePosted) {
                    Choreographer.getInstance().removeFrameCallback(displayFrame)
                    framePosted = false
                } else {
                    ensureFramePosted()
                }
            }
        }
    }

    private fun ensureFramePosted() {
        if (!framePosted && callbacks.isNotEmpty()) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(displayFrame)
        }
    }

    private const val TAG = "RWPP-HudFrameClock"
}

internal actual fun registerHudFrame(callback: Runnable): AutoCloseable = HudFrameClock.register(callback)

internal actual fun requestWorldPresentationFrame() {
    Unit
}
