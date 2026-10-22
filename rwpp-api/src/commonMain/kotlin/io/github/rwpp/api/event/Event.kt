/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/** Base contract for events dispatched by [EventChannel] or [GlobalEventChannel]. */
interface Event {
    val isIntercepted: Boolean
    val isCancelled: Boolean

    /** Decision events can opt into fail-closed listener exceptions. */
    val propagateListenerFailures: Boolean
        get() = false

    fun intercept()
    fun cancel()
}

/** Base implementation shared by notification and cancellable events. */
abstract class AbstractEvent : Event {
    @Suppress("PropertyName")
    @Volatile
    internal var _intercepted: Boolean = false

    @Suppress("PropertyName")
    @Volatile
    internal var _cancelled: Boolean = false

    override val isIntercepted: Boolean
        get() = _intercepted

    override val isCancelled: Boolean
        get() = _cancelled

    override fun intercept() {
        _intercepted = true
    }

    override fun cancel() {
        _cancelled = true
    }

    /** Clears only the reusable control flags; event payload owners remain responsible for data. */
    internal fun resetControlState() {
        _intercepted = false
        _cancelled = false
    }
}

/**
 * Optional control for events that can suppress later ordinary listeners after the first handler.
 * MONITOR listeners still observe the event.
 */
interface BroadcastControllable : Event {
    val shouldBroadcast: Boolean
        get() = true
}

/** Suspend until all listeners have handled this event. Dispatch remains in the caller coroutine. */
suspend fun <E : Event> E.broadcast(): E {
    GlobalEventChannel.broadcast(this)
    return this
}

/** Starts an asynchronous global broadcast and returns the event immediately. */
@JvmOverloads
@Suppress("unused")
fun <E : Event> E.broadcastIn(
    onFinished: (E) -> Unit = {},
    delay: Duration = 0.milliseconds,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
): E {
    GlobalEventChannel.launch(coroutineContext) {
        if (delay.toLong(DurationUnit.MILLISECONDS) > 0) delay(delay)
        broadcast()
    }.invokeOnCompletion { onFinished(this) }
    return this
}
