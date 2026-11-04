/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.event.OnDispose
import io.github.rwpp.event.events.KeyboardEvent

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val callback = rememberUpdatedState(onBack)
    if (enabled) {
        GlobalEventChannel.filter(KeyboardEvent::class).OnDispose {
            subscribeAlways { event ->
                if (event.keyEvent.key == Key.Escape && event.keyEvent.type == KeyEventType.KeyDown &&
                    io.github.rwpp.ui.UI.globalNavigator.current !is io.github.rwpp.ui.GameScreen
                ) {
                    callback.value()
                }
            }
        }
    }
}
