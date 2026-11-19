/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.platform

/** A later enabled handler owns Back; disposing it restores the enclosing handler. */
internal class BackActionDispatcher {
    private class Handler(val enabled: () -> Boolean, val onBack: () -> Unit)

    private val handlers = mutableListOf<Handler>()

    fun register(enabled: () -> Boolean = { true }, onBack: () -> Unit): () -> Unit {
        val handler = Handler(enabled, onBack)
        synchronized(handlers) { handlers += handler }
        return {
            synchronized(handlers) { handlers.remove(handler) }
            Unit
        }
    }

    fun dispatch(): Boolean {
        val handler = synchronized(handlers) { handlers.lastOrNull { it.enabled() } } ?: return false
        handler.onBack()
        return true
    }
}

internal val desktopBackDispatcher = BackActionDispatcher()

/** Compose dialogs and focused controls precede page Back handlers, which precede game hotkeys. */
internal fun routeDesktopKeyEvent(
    escapeDown: Boolean,
    sceneConsumed: Boolean,
    gameInputBlocked: Boolean,
    forwardToGame: Boolean = true,
    dispatchBack: () -> Boolean,
    broadcastToGame: () -> Unit,
): Boolean {
    val consumed = sceneConsumed || (escapeDown && dispatchBack())
    if (!consumed && !gameInputBlocked && forwardToGame) broadcastToGame()
    return consumed || gameInputBlocked
}
