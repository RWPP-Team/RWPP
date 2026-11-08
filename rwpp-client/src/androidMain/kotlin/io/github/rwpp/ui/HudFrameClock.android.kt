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
 * Native world draws drive HUD sampling without a second display-clock gate. The display clock
 * only queues a coalesced fallback when native drawing stops (menus, paused/hidden surfaces).
 */
internal object HudFrameClock {
    private val callbacks = CopyOnWriteArrayList<Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private val frameQueued = AtomicBoolean(false)
    private val schedule = NativeHudFrameSchedule()
    private var framePosted = false // Choreographer scheduling is confined to the main looper.

    private val gameFrame = Runnable {
        // A native frame end may already have run this batch; only the first claimant runs it.
        if (!frameQueued.compareAndSet(true, false)) return@Runnable
        if (schedule.needsDisplayFallback(System.nanoTime())) runBatch()
    }

    private fun runBatch() {
        callbacks.forEach { callback ->
            try {
                callback.run()
            } catch (failure: Throwable) {
                Log.e(TAG, "Error running a HUD frame callback", failure)
            }
        }
    }

    /**
     * Called after native world drawing, on its owner thread. Do not require a pending display
     * callback: a 120 Hz game must still produce 120 HUD snapshots when the window callback is
     * slower or delayed. Queued fallbacks cannot replay an older batch on the next update.
     */
    fun flushPending() {
        schedule.onNativeFrame(System.nanoTime())
        frameQueued.set(false)
        runBatch()
    }

    private val displayFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePosted = false
            if (callbacks.isEmpty()) return

            if (schedule.needsDisplayFallback(System.nanoTime()) && frameQueued.compareAndSet(false, true)) {
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

internal actual fun flushPendingHudFrame() = HudFrameClock.flushPending()

/**
 * The Compose layer is a separate window layer above the game surface. Re-running its draw pass
 * makes the world overlay paint the camera captured by the native frame that was just presented,
 * instead of waiting for the next state invalidation.
 */
internal actual fun requestWorldPresentationFrame() = Unit // WorldCanvas draws in the native buffer.
