/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game

import com.corrodinggames.rts.gameFramework.GameEngine
import coil3.SingletonImageLoader
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.graphics.ActionImages

internal actual suspend fun <T> runNativeModWork(reloadData: Boolean, action: () -> T): T =
    GameMainDispatcher.runIdleGameWork(action)

internal actual fun presentNativeModProgress() = Unit

/**
 * The Android host records frames onto a hardware canvas from its own thread while mod data is
 * replaced on the game thread. Without the render lock a frame can walk a half-rebuilt unit type
 * table or a recycled bitmap, which is the crash the mod reload used to produce. Native text and
 * icon caches also hold the old definitions, so they are dropped once the swap completed.
 */
internal actual fun withNativeModReloadGraphics(engine: GameEngine, reload: () -> Unit) {
    ActionImages.clear()
    SingletonImageLoader.get(engine.context.applicationContext).memoryCache?.clear()
    synchronized(engine.renderLock) {
        reload()
    }
}
