/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.State
import io.github.rwpp.api.event.GameEventPublisher
import java.util.concurrent.CopyOnWriteArraySet

class GameState {
    private var sandboxFallback: Boolean = false
    var sandbox: Boolean
        get() = nativeModeOrNull { engine -> engine.networkEngine.sandboxMode || engine.debugEditorMode }
            ?: sandboxFallback
        set(value) {
            sandboxFallback = value
        }

    private var multiplayerFallback: Boolean = false
    var multiplayer: Boolean
        get() = nativeModeOrNull { engine ->
            engine.networkEngine.networked && !engine.networkEngine.singlePlayerServer
        } ?: multiplayerFallback
        set(value) {
            multiplayerFallback = value
        }

    /**
     * ReplayEngine.isPlayingReplay() can remain true after a replay load fails, so this mode is
     * driven by the successful native lifecycle transition rather than the engine's raw flag.
     */
    var replay: Boolean = false
    var gameOver: Boolean = false
    var mods: List<String> = listOf()
    val defeatedPlayerSet = CopyOnWriteArraySet<Team>()
    var state: State = State.Menu
        set(value) {
            if (field == value) return
            field = value
            GameEventPublisher.stateChanged(value)
        }

    fun isPlaying(): Boolean = state == State.Playing
    fun isMenu(): Boolean = state == State.Menu
    fun isPaused(): Boolean = state == State.Paused

    private inline fun nativeModeOrNull(mode: (GameEngine) -> Boolean): Boolean? = runCatching {
        mode(GameEngine.getInstance())
    }.getOrNull()
}
