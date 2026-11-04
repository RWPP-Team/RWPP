/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event

import io.github.rwpp.api.logging.logger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

private val eventLog = logger()
private val priorityOrder = EventPriority.entries.toTypedArray()

/**
 * A coroutine-aware event channel. `broadcast` invokes listeners in priority order in the
 * publisher coroutine. A listener switches threads only when it has an explicit dispatcher in
 * its context.
 */
open class EventChannel<T : Event>(
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    errorHandler: (Event, Throwable) -> Unit = { event, failure ->
        eventLog.error("Event listener failed for ${event.javaClass.simpleName}.", failure)
    },
) : CoroutineScope by coroutineScope {
    private data class Entry(
        val listener: Listener<*>,
        val handler: suspend (Event) -> Unit,
        val eventType: Class<out Event>,
        val priority: EventPriority,
        val dispatchContext: CoroutineContext,
    )

    /**
     * Listener membership changes are rare compared with broadcasts. Publish immutable arrays so
     * the dispatch and listener-count hot paths need neither a CopyOnWriteArraySet iterator nor a
     * per-broadcast snapshot allocation.
     */
    private class PriorityListeners(private val registrationCount: AtomicInteger) {
        private val lock = Any()

        @Volatile
        var entries: Array<Entry> = emptyArray()
            private set

        fun add(entry: Entry) = synchronized(lock) {
            val updated = entries + entry
            registrationCount.incrementAndGet()
            entries = updated
        }

        fun remove(entry: Entry) = synchronized(lock) {
            val current = entries
            val index = current.indexOfFirst { it === entry }
            if (index >= 0) {
                val updated = arrayOfNulls<Entry>(current.size - 1)
                if (index > 0) current.copyInto(updated, endIndex = index)
                if (index < current.lastIndex) {
                    current.copyInto(updated, destinationOffset = index, startIndex = index + 1)
                }
                @Suppress("UNCHECKED_CAST")
                entries = updated as Array<Entry>
                registrationCount.decrementAndGet()
            }
        }
    }

    private val registrationCount = AtomicInteger()
    private val listeners = Array(priorityOrder.size) { PriorityListeners(registrationCount) }

    @Volatile
    var errorHandler: (Event, Throwable) -> Unit = errorHandler

    /** True when this channel has at least one active listener that can still be dispatched. */
    val hasListeners: Boolean
        get() {
            if (registrationCount.get() == 0) return false
            for (group in listeners) {
                val entries = group.entries
                for (index in entries.indices) {
                    if (entries[index].listener.canDispatch()) return true
                }
            }
            return false
        }

    /** Checks whether an active listener accepts [eventType] or a supertype of it. */
    open fun hasListeners(eventType: KClass<out Event>): Boolean = hasListeners(eventType.java)

    /** Checks whether an active listener accepts [eventType] or a supertype of it. */
    open fun hasListeners(eventType: Class<out Event>): Boolean {
        if (registrationCount.get() == 0) return false
        for (group in listeners) {
            val entries = group.entries
            for (index in entries.indices) {
                val entry = entries[index]
                if (entry.listener.canDispatch() && entry.eventType.isAssignableFrom(eventType)) return true
            }
        }
        return false
    }

    /** Dispatches [event] to this channel and waits for every eligible listener. */
    open suspend fun broadcast(event: T): T = dispatch(event)

    private suspend fun dispatch(event: T): T {
        for (priorityIndex in priorityOrder.indices) {
            val priority = priorityOrder[priorityIndex]
            val entries = listeners[priorityIndex].entries
            for (entryIndex in entries.indices) {
                val entry = entries[entryIndex]
                val listener = entry.listener
                if (!listener.canDispatch()) continue
                if (event.isIntercepted && priority != EventPriority.MONITOR) continue
                if (!entry.eventType.isInstance(event)) continue
                if (!listener.claimForDispatch()) continue

                try {
                    // Do not replace the publisher Job with the subscription Job. This keeps
                    // caller cancellation intact while still applying an explicit dispatcher.
                    if (entry.dispatchContext === EmptyCoroutineContext) {
                        entry.handler(event)
                    } else {
                        withContext(entry.dispatchContext) {
                            entry.handler(event)
                        }
                    }
                } catch (failure: Throwable) {
                    if (event.propagateListenerFailures) throw failure
                    if (failure is CancellationException && !currentCoroutineContext().isActive) {
                        throw failure
                    }
                    reportFailure(event, failure)
                } finally {
                    if (listener.once) listener.complete()
                }

                if (event is BroadcastControllable && !event.shouldBroadcast) event.intercept()
            }
        }
        return event
    }

    private fun reportFailure(event: Event, failure: Throwable) {
        if (failure is CancellationException) return
        try {
            errorHandler(event, failure)
        } catch (handlerFailure: Throwable) {
            eventLog.error("Could not report listener failure for ${event.javaClass.simpleName}.", handlerFailure)
        }
    }

    /** Receives the next event handled by this channel. */
    suspend fun receive(
        timeout: Long = -1,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): T? {
        val result = CompletableDeferred<T>()
        val listener = registerListener(
            coroutineContext = coroutineContext,
            priority = EventPriority.MONITOR,
            status = ListeningStatus.STOPPED,
        ) { event -> result.complete(event) }
        return try {
            awaitResult(timeout) { result.await() }
        } finally {
            listener.close()
        }
    }

    /** Registers a listener for every event accepted by this channel. */
    fun subscribeAlways(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerAlways(null, coroutineContext, priority, handler)

    /** Registers an extension-owned listener. [owner] closes it when the extension unloads. */
    fun subscribeAlways(
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerAlways(owner, coroutineContext, priority, handler)

    /** Registers a listener that is atomically claimed before its first callback. */
    fun subscribeOnce(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerOnce(null, coroutineContext, priority, handler)

    /** Registers an extension-owned one-shot listener. */
    fun subscribeOnce(
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerOnce(owner, coroutineContext, priority, handler)

    /** Backward-compatible listener registration API used by existing consumers. */
    fun registerListener(
        coroutineContext: CoroutineContext,
        priority: EventPriority,
        status: ListeningStatus,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerListener(
        owner = null,
        coroutineContext = coroutineContext,
        priority = priority,
        status = status,
        once = status == ListeningStatus.STOPPED,
        eventType = Event::class.java,
        handler = handler,
    )

    /** Removes a listener by closing its returned handle. */
    fun unregisterListener(listener: Listener<T>) {
        listener.close()
    }

    internal fun <E : Event> registerTyped(
        eventType: Class<E>,
        owner: EventOwner? = null,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        once: Boolean = false,
        handler: suspend (E) -> Unit,
    ): Listener<E> {
        return registerListener(
            eventType = eventType,
            owner = owner,
            coroutineContext = coroutineContext,
            priority = priority,
            status = if (once) ListeningStatus.STOPPED else ListeningStatus.LISTENING,
            once = once,
            handler = handler,
        )
    }

    protected suspend fun broadcastTyped(event: Event): Event {
        @Suppress("UNCHECKED_CAST")
        return broadcast(event as T)
    }

    private fun registerAlways(
        owner: EventOwner?,
        context: CoroutineContext,
        priority: EventPriority,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerListener(
        owner = owner,
        coroutineContext = context,
        priority = priority,
        status = ListeningStatus.LISTENING,
        once = false,
        handler = handler,
    )

    private fun registerOnce(
        owner: EventOwner?,
        context: CoroutineContext,
        priority: EventPriority,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerListener(
        owner = owner,
        coroutineContext = context,
        priority = priority,
        status = ListeningStatus.STOPPED,
        once = true,
        handler = handler,
    )

    private fun <E : Event> registerListener(
        owner: EventOwner?,
        coroutineContext: CoroutineContext,
        priority: EventPriority,
        status: ListeningStatus,
        once: Boolean,
        eventType: Class<out Event> = Event::class.java,
        handler: suspend (E) -> Unit,
    ): Listener<E> {
        val listenerContext = coroutineContext.minusKey(Job)
        val listener = object : Listener<E>() {
            override val coroutineContext: CoroutineContext
                get() = this + listenerContext

            override val priority: EventPriority
                get() = priority

            override suspend fun onEvent(event: E): ListeningStatus {
                handler(event)
                return status
            }
        }.apply { this.once = once }

        @Suppress("UNCHECKED_CAST")
        val eventHandler = handler as suspend (Event) -> Unit
        val entry = Entry(listener, eventHandler, eventType, priority, listenerContext)
        listeners[priority.ordinal].add(entry)
        listener.attach {
            listeners[priority.ordinal].remove(entry)
            owner?.release(listener)
        }
        owner?.own(listener)
        return listener
    }

    private suspend fun <R> awaitResult(timeout: Long, block: suspend () -> R): R? =
        if (timeout > 0) withTimeoutOrNull(timeout) { block() } else block()
}
