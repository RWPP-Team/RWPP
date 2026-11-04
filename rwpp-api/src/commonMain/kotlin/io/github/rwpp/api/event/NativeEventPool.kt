/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event

/**
 * Per-thread, depth-indexed storage for events broadcast synchronously from native hooks.
 * Nested broadcasts borrow another slot; concurrent native threads never share an event object.
 */
internal class NativeEventPool<E>(
    private val create: () -> E,
    private val clearPayload: (E) -> Unit,
) where E : AbstractEvent {
    private class State<E> {
        val events = ArrayList<E>(2)
        var depth: Int = 0
    }

    private val local = ThreadLocal.withInitial { State<E>() }

    fun acquire(): E {
        val state = local.get()
        val index = state.depth++
        val event = if (index < state.events.size) {
            state.events[index]
        } else {
            create().also(state.events::add)
        }
        event.resetControlState()
        return event
    }

    fun release(event: E) {
        val state = local.get()
        check(state.depth > 0) { "No native event is currently borrowed on this thread" }
        val index = state.depth - 1
        check(state.events[index] === event) { "Native events must be returned in nested order" }
        clearPayload(event)
        event.resetControlState()
        state.depth = index
    }
}
