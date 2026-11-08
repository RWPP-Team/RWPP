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
 * The host keeps a client in "Still waiting on" until packet 112 reports `gameLoopActive`, so a
 * single early packet is not enough. Nothing about the host is observable here, therefore the ack
 * repeats a bounded number of times instead of giving up after three failed attempts.
 */
internal class NetworkClientReadyAck {
    private var pending = false
    private var mapLoadCompleted = false
    private var cooldownFrames = 0
    private var sendsRemaining = 0

    @Synchronized
    fun begin(isNetworkedClient: Boolean) {
        pending = isNetworkedClient
        mapLoadCompleted = false
        cooldownFrames = 0
        sendsRemaining = MAX_SENDS
    }

    @Synchronized
    fun markMapLoaded() {
        if (pending) mapLoadCompleted = true
    }

    @Synchronized
    fun clear() {
        pending = false
        mapLoadCompleted = false
        cooldownFrames = 0
        sendsRemaining = 0
    }

    @Synchronized
    fun isPending(): Boolean = pending

    @Synchronized
    fun beginAttemptIfReady(
        isNetworkedClient: Boolean,
        gameStarted: Boolean,
        engineMapLoaded: Boolean,
        gameLoopActive: Boolean,
    ): Boolean {
        if (!pending || !isNetworkedClient || !gameStarted || !mapLoadCompleted || !engineMapLoaded ||
            !gameLoopActive
        ) return false
        if (sendsRemaining <= 0) {
            pending = false
            mapLoadCompleted = false
            return false
        }
        if (cooldownFrames > 0) {
            cooldownFrames--
            return false
        }
        return true
    }

    @Synchronized
    fun markSent() {
        sendsRemaining--
        if (sendsRemaining <= 0) {
            pending = false
            mapLoadCompleted = false
            cooldownFrames = 0
        } else {
            cooldownFrames = CONFIRM_RESEND_FRAMES
        }
    }

    /** Returns whether a later frame may retry the native send. */
    @Synchronized
    fun markAttemptFailed(): Boolean {
        if (pending) cooldownFrames = RETRY_COOLDOWN_FRAMES
        return pending
    }

    private companion object {
        /** Packet 112 is reliable, but a resend covers a session race on the host side. */
        const val MAX_SENDS = 3
        const val RETRY_COOLDOWN_FRAMES = 30
        const val CONFIRM_RESEND_FRAMES = 90
    }
}
