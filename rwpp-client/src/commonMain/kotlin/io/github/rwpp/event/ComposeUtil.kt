/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.rwpp.api.event.Event
import io.github.rwpp.api.event.EventChannel
import io.github.rwpp.api.event.Listener

/**
 * Make event listening follow the compose lifecycle.
 */
@Composable
inline fun <T : Event> EventChannel<T>.OnDispose(
    crossinline listenerProvider: EventChannel<T>.() -> Listener<T>
) {
    DisposableEffect(Unit) {
        val listener = listenerProvider()
        onDispose {
            unregisterListener(listener)
        }
    }
}