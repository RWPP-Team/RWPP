/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Whether a listener remains registered after handling an event. */
enum class ListeningStatus {
    LISTENING,
    STOPPED,
}

/** An event subscription that is also a coroutine job and an [AutoCloseable] resource. */
abstract class Listener<in E : Event> : CompletableJob by Job(), AutoCloseable {
    private val onceClaimed = AtomicBoolean(false)
    private val detachLock = Any()
    private var detached = false
    private var detachAction: (() -> Unit)? = null

    /** Event priority. Higher priority listeners run first. */
    open val priority: EventPriority get() = EventPriority.NORMAL

    /** Additional context for this listener; omitted dispatchers inherit the publisher's context. */
    open val coroutineContext: CoroutineContext get() = this

    abstract suspend fun onEvent(event: E): ListeningStatus

    internal var once: Boolean = false

    init {
        invokeOnCompletion { detach() }
    }

    internal fun attach(detachAction: () -> Unit) {
        val runNow = synchronized(detachLock) {
            this.detachAction = detachAction
            if (detached || !isActive) {
                detached = true
                true
            } else false
        }
        if (runNow) detachAction()
    }

    internal fun canDispatch(): Boolean = isActive && (!once || !onceClaimed.get())

    /** Claims a one-shot subscription before invoking it, so recursive broadcasts see it as used. */
    internal fun claimForDispatch(): Boolean {
        if (!isActive) return false
        return !once || onceClaimed.compareAndSet(false, true)
    }

    private fun detach() {
        val action = synchronized(detachLock) {
            if (detached) return
            detached = true
            detachAction
        }
        action?.invoke()
    }

    override fun close() {
        cancel()
    }
}

/** Listener ordering and interception rules used by event channels. */
enum class EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST,
    MONITOR;

    companion object {
        val all: Array<EventPriority> = entries.toTypedArray()
    }
}
