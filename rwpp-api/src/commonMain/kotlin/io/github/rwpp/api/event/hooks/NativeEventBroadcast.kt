/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.event.Event
import io.github.rwpp.api.event.GlobalEventChannel
import kotlin.reflect.KClass

/** Waits for native-boundary listeners without forcing them onto a different dispatcher. */
private val eventBroadcaster: suspend Event.() -> Event = GlobalEventChannel.Companion::broadcast

internal fun <T : Event> broadcastNativeEvent(event: T): T {
    @Suppress("UNCHECKED_CAST")
    return GameMainDispatcher.runBlockingWithGameDispatcher(eventBroadcaster, event) as T
}

internal fun hasNativeEventListeners(eventType: KClass<out Event>): Boolean =
    GlobalEventChannel.hasListeners(eventType)
