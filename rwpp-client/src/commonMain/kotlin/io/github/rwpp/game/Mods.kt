/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameMainDispatcher
import kotlinx.coroutines.withContext

/** Desktop texture uploads require the native GL owner and a neutral unpack state. */
internal expect fun withNativeModReloadGraphics(engine: GameEngine, reload: () -> Unit)

internal fun reloadModDataOnGameThread(engine: GameEngine) {
    withNativeModReloadGraphics(engine) {
        engine.modManager.reloadModsAndSelection()
        engine.modManager.reloadModData()
    }
}

/** Reload the engine's mod selection and data on the game thread. */
suspend fun reloadMods() {
    withContext(GameMainDispatcher) {
        val engine = GameEngine.getInstance()
        engine.modManager.saveSelection()
        check(engine.settings.save()) { "Could not save mod selection" }
        reloadModDataOnGameThread(engine)
    }
}
