/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import android.os.Looper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.os.HandlerCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The Aero HUD is a Compose layer in the window, above the game surface. The native frame has
 * already been presented by the time the world snapshot is published, so the layer has to run
 * its draw pass again to show the overlay on the same picture instead of one frame later.
 */
internal object AndroidHudSurface {
    // A normal Handler can sit behind ViewRoot's vsync synchronization barrier until after
    // traversal, adding a whole frame even at 60 Hz. This notification must reach draw first.
    private val handler = HandlerCompat.createAsync(Looper.getMainLooper())
    private val target = AtomicReference<View?>(null)
    private val drawQueued = AtomicBoolean(false)
    private val draw = Runnable {
        drawQueued.set(false)
        val view = target.get() ?: return@Runnable
        if (!view.isAttachedToWindow) return@Runnable
        // Publish native-thread snapshot writes before the owner records its display list.
        Snapshot.sendApplyNotifications()
        // ComposeView is a container. Invalidating it alone can reuse AndroidComposeView's old
        // display list, leaving world effects at the previous camera until Compose wakes up.
        val owner = (view as? ViewGroup)?.getChildAt(0)
        owner?.invalidate()
        view.invalidate()
    }

    fun attach(view: View) {
        target.set(view)
        // Vote for the active display cadence, including 60 Hz. Do not force a faster display
        // mode merely because a passive HUD is attached above the game's SurfaceView.
        if (Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(view.display?.refreshRate ?: View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    fun detach(view: View) {
        if (target.compareAndSet(view, null) && Build.VERSION.SDK_INT >= 35) {
            view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    fun requestDraw() {
        if (target.get() != null && drawQueued.compareAndSet(false, true)) handler.post(draw)
    }
}
