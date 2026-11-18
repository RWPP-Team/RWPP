/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

/**
 * Ensures the native client-ready packet is sent after the newly loaded game can run.
 *
 * A completed local send does not prove that the host counted packet 112. Allow bounded
 * retries until the host advances the match, including when the native path sent ready first.
 */
internal class NetworkClientReadyAck(private val nanoTime: () -> Long = System::nanoTime) {
    private var pending = false
    private var mapLoadCompleted = false
    private var sendsRemaining = 0
    private var nextSendAt: Long? = null

    @Synchronized
    fun begin(isNetworkedClient: Boolean) {
        pending = isNetworkedClient
        mapLoadCompleted = false
        sendsRemaining = if (isNetworkedClient) MAX_SENDS else 0
        nextSendAt = null
    }

    @Synchronized
    fun markMapLoaded() {
        if (pending) mapLoadCompleted = true
    }

    @Synchronized
    fun clear() {
        pending = false
        mapLoadCompleted = false
        sendsRemaining = 0
        nextSendAt = null
    }

    @Synchronized
    fun isPending(): Boolean = pending

    @Synchronized
    fun canCheckReady(): Boolean = mapLoadCompleted && sendsRemaining > 0

    @Synchronized
    fun beginAttempt(): Boolean {
        if (!canCheckReady()) return false
        val now = nanoTime()
        if (nextSendAt?.let { now - it < 0 } == true) return false
        nextSendAt = now + RETRY_FAILED_SEND_NANOS
        return true
    }

    @Synchronized
    fun onReadyStateSent(gameLoopActive: Boolean): Boolean {
        if (!canCheckReady() || !gameLoopActive) return false
        sendsRemaining--
        nextSendAt = nanoTime() + RETRY_READY_NANOS
        val wasPending = pending
        pending = false
        return wasPending
    }

    @Synchronized
    fun stopRetriesAfterServerStep(): Boolean {
        if (pending) return false
        clear()
        return true
    }

    private companion object {
        const val MAX_SENDS = 3
        const val RETRY_READY_NANOS = 1_500_000_000L
        const val RETRY_FAILED_SEND_NANOS = 500_000_000L
    }
}
