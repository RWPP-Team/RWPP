/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwml.api.ui.UiSurface
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the user-confirmed close path while still delegating a confirmed close to the host surface. */
internal class SurfaceCloseController(
    private val surface: UiSurface,
    private val confirmExit: () -> Boolean
) {
    private val closeRequested = AtomicBoolean(false)

    fun requestClose() {
        if (closeRequested.get() || surface.isClosed) return
        if (confirmExit() && closeRequested.compareAndSet(false, true)) {
            surface.close()
        }
    }
}
