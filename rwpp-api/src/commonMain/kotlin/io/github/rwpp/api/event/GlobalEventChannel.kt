/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package io.github.rwpp.api.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * Process-wide event channel. Global listeners run first, followed by listeners in the exact
 * class filter returned by [filter]. Both groups use the same suspend listener dispatcher.
 */
sealed class GlobalEventChannel(coroutineScope: CoroutineScope) : EventChannel<Event>(coroutineScope) {
    private val channels = ConcurrentHashMap<Class<out Event>, EventChannel<Event>>()

    /** Broadcasts synchronously in the caller coroutine; it does not enqueue a flow emission. */
    override suspend fun broadcast(event: Event): Event =
        if (super.hasListeners) broadcastGlobalThenFiltered(event)
        else channels[event.javaClass]?.broadcast(event) ?: event

    private suspend fun broadcastGlobalThenFiltered(event: Event): Event {
        super.broadcast(event)
        val filteredChannel = channels[event.javaClass] ?: return event
        return filteredChannel.broadcast(event)
    }

    /**
     * Gets the channel for exactly [eventClass]. Subclass events are dispatched to their exact
     * class filter only; use `subscribeGlobalAlways<BaseEvent>` for inherited event matching.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Event> filter(eventClass: KClass<T>): EventChannel<T> =
        channels.computeIfAbsent(eventClass.java) { EventChannel(coroutineScope) } as EventChannel<T>

    /**
     * Returns true for an active global listener that accepts this class or one of its supertypes,
     * or for an active subscription in this event's exact class filter.
     */
    override fun hasListeners(eventType: KClass<out Event>): Boolean =
        hasListeners(eventType.java)

    /** JVM class overload for callers that already hold a native class token. */
    override fun hasListeners(eventType: Class<out Event>): Boolean =
        super.hasListeners(eventType) || channels[eventType]?.hasListeners == true

    /** Waits for the next event of [eventClass] or one of its subtypes. */
    suspend fun <T : Event> receiveGlobal(
        eventClass: KClass<T>,
        timeout: Long = -1,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): T? {
        val result = CompletableDeferred<T>()
        val listener = subscribeGlobalOnce(
            eventClass = eventClass,
            coroutineContext = coroutineContext,
            priority = EventPriority.MONITOR,
        ) { event -> result.complete(event) }
        return listener.use {
            if (timeout > 0) withTimeoutOrNull(timeout.milliseconds) { result.await() } else result.await()
        }
    }

    /** Registers a listener for [T] and all its subclasses. */
    inline fun <reified T : Event> subscribeGlobalAlways(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        crossinline handler: suspend (T) -> Unit,
    ): Listener<T> = subscribeGlobalAlways(T::class, coroutineContext, priority) { handler(it) }

    /** Registers an extension-owned global listener for [T] and all its subclasses. */
    inline fun <reified T : Event> subscribeGlobalAlways(
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        crossinline handler: suspend (T) -> Unit,
    ): Listener<T> = subscribeGlobalAlways(T::class, owner, coroutineContext, priority) { handler(it) }

    /** KClass-based global listener registration. */
    fun <T : Event> subscribeGlobalAlways(
        eventClass: KClass<T>,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerGlobalAlways(eventClass, null, coroutineContext, priority, handler)

    /** KClass-based extension-owned global listener registration. */
    fun <T : Event> subscribeGlobalAlways(
        eventClass: KClass<T>,
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerGlobalAlways(eventClass, owner, coroutineContext, priority, handler)

    /** Java class-based global listener registration. */
    fun <T : Event> subscribeGlobalAlways(
        eventClass: Class<T>,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass,
        coroutineContext = coroutineContext,
        priority = priority,
        once = false,
        handler = handler,
    )

    /** Java class-based extension-owned global listener registration. */
    fun <T : Event> subscribeGlobalAlways(
        eventClass: Class<T>,
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass,
        owner = owner,
        coroutineContext = coroutineContext,
        priority = priority,
        once = false,
        handler = handler,
    )

    /** Registers an atomically claimed one-shot listener for [T] and all its subclasses. */
    inline fun <reified T : Event> subscribeGlobalOnce(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        crossinline handler: suspend (T) -> Unit,
    ): Listener<T> = subscribeGlobalOnce(T::class, coroutineContext, priority) { handler(it) }

    /** Registers an extension-owned global one-shot listener. */
    inline fun <reified T : Event> subscribeGlobalOnce(
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        crossinline handler: suspend (T) -> Unit,
    ): Listener<T> = subscribeGlobalOnce(T::class, owner, coroutineContext, priority) { handler(it) }

    /** KClass-based one-shot listener registration. */
    fun <T : Event> subscribeGlobalOnce(
        eventClass: KClass<T>,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerGlobalOnce(eventClass, null, coroutineContext, priority, handler)

    /** KClass-based extension-owned one-shot listener registration. */
    fun <T : Event> subscribeGlobalOnce(
        eventClass: KClass<T>,
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerGlobalOnce(eventClass, owner, coroutineContext, priority, handler)

    /** Java class-based one-shot listener registration. */
    fun <T : Event> subscribeGlobalOnce(
        eventClass: Class<T>,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass,
        coroutineContext = coroutineContext,
        priority = priority,
        once = true,
        handler = handler,
    )

    /** Java class-based extension-owned one-shot listener registration. */
    fun <T : Event> subscribeGlobalOnce(
        eventClass: Class<T>,
        owner: EventOwner,
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        priority: EventPriority = EventPriority.NORMAL,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass,
        owner = owner,
        coroutineContext = coroutineContext,
        priority = priority,
        once = true,
        handler = handler,
    )

    private fun <T : Event> registerGlobalAlways(
        eventClass: KClass<T>,
        owner: EventOwner?,
        coroutineContext: CoroutineContext,
        priority: EventPriority,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass.java,
        owner = owner,
        coroutineContext = coroutineContext,
        priority = priority,
        once = false,
        handler = handler,
    )

    private fun <T : Event> registerGlobalOnce(
        eventClass: KClass<T>,
        owner: EventOwner?,
        coroutineContext: CoroutineContext,
        priority: EventPriority,
        handler: suspend (T) -> Unit,
    ): Listener<T> = registerTyped(
        eventType = eventClass.java,
        owner = owner,
        coroutineContext = coroutineContext,
        priority = priority,
        once = true,
        handler = handler,
    )

    companion object : GlobalEventChannel(CoroutineScope(SupervisorJob()))
}
