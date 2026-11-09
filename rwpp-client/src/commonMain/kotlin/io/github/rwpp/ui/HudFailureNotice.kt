/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.logging.logger
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong

/** A failed passive HUD callback is removed by its owner; simulation state remains untouched. */
private val lastHudFailureNotice = AtomicLong()
internal fun warnHudFailure(source: String, failure: Throwable) {
    var error = failure
    while (error is InvocationTargetException) {
        val cause = error.targetException ?: break
        if (cause === error) break
        error = cause
    }
    if (error is VirtualMachineError || error is ThreadDeath) throw error
    logger().warn("Stopped failing $source callback", error)
    val now = System.nanoTime()
    val previous = lastHudFailureNotice.get()
    if (previous == 0L || now - previous >= 2_000_000_000L) {
        if (lastHudFailureNotice.compareAndSet(previous, now)) {
            UI.showNativeMessage(
                null, readI18n(
                    "rwpp.gameError.hud",
                    "$source: ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(600)
                )
            )
        }
    }
}
