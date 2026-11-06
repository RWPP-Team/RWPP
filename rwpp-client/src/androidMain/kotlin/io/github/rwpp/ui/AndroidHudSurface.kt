/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.atomic.AtomicReference

/**
 * The Aero HUD is a Compose layer in the window, above the game surface. The native frame has
 * already been presented by the time the world snapshot is published, so the layer has to run
 * its draw pass again to show the overlay on the same picture instead of one frame later.
 */
internal object AndroidHudSurface {
    private val handler = Handler(Looper.getMainLooper())
    private val target = AtomicReference<View?>(null)

    fun attach(view: View) {
        target.set(view)
    }

    fun detach(view: View) {
        target.compareAndSet(view, null)
    }

    fun requestDraw() {
        val view = target.get() ?: return
        handler.post {
            if (target.get() === view && view.isAttachedToWindow) view.invalidate()
        }
    }
}
