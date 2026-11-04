/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game

/** Thread-safe native network-game map-load marker used while the engine loads a received start packet. */
object NetworkGameLoadingState {
    @Volatile
    var isLoadingMap: Boolean = false
        private set

    internal fun beginMapLoad() {
        isLoadingMap = true
    }

    internal fun finishMapLoad() {
        isLoadingMap = false
    }
}
