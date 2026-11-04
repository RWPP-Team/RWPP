/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.impl

/** Ensures the native client-ready packet is sent once, after the newly loaded game can run. */
internal class NetworkClientReadyAck {
    private var pending = false
    private var mapLoadCompleted = false
    private var attempts = 0

    @Synchronized
    fun begin(isNetworkedClient: Boolean) {
        pending = isNetworkedClient
        mapLoadCompleted = false
        attempts = 0
    }

    @Synchronized
    fun markMapLoaded() {
        if (pending) mapLoadCompleted = true
    }

    @Synchronized
    fun clear() {
        pending = false
        mapLoadCompleted = false
    }

    @Synchronized
    fun isPending(): Boolean = pending

    @Synchronized
    fun beginAttemptIfReady(
        isNetworkedClient: Boolean,
        gameStarted: Boolean,
        engineMapLoaded: Boolean,
        gameLoopActive: Boolean,
        gameStatePlaying: Boolean,
    ): Boolean {
        if (!pending || !isNetworkedClient || !gameStarted || !mapLoadCompleted || !engineMapLoaded ||
            !gameLoopActive || !gameStatePlaying
        ) return false
        if (attempts >= MAX_SEND_ATTEMPTS) {
            pending = false
            mapLoadCompleted = false
            return false
        }
        attempts++
        return true
    }

    @Synchronized
    fun markSent() {
        pending = false
        mapLoadCompleted = false
    }

    /** Returns whether a later frame may retry the bounded native send. */
    @Synchronized
    fun markAttemptFailed(): Boolean {
        if (attempts >= MAX_SEND_ATTEMPTS) {
            pending = false
            mapLoadCompleted = false
        }
        return pending
    }

    private companion object {
        const val MAX_SEND_ATTEMPTS = 3
    }
}
