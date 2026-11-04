/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.game.room

import com.corrodinggames.rts.gameFramework.GameEngine

internal data class PauseMenuOptions(
    val chat: Boolean = false, val players: Boolean = false, val returnRoom: Boolean = false,
    val surrender: Boolean = false, val briefing: Boolean = false, val hideInterface: Boolean = false,
)

/** Mirrors GameActivity.onPrepareOptionsMenu and its host-only return-to-room confirmation. */
internal fun pauseMenuOptions(
    multiplayer: Boolean, host: Boolean, defeated: Boolean,
    victoryShown: Boolean, replay: Boolean, hasBriefing: Boolean
): PauseMenuOptions =
    PauseMenuOptions(
        multiplayer, multiplayer, multiplayer && host,
        multiplayer && !defeated && !victoryShown, !multiplayer && hasBriefing, replay
    )

internal fun nativePauseMenuOptions(engine: GameEngine): PauseMenuOptions = pauseMenuOptions(
    engine.isMultiplayer, engine.networkEngine.isServer, engine.playerTeam?.wipedOut == true,
    engine.victoryScreenShown, engine.replayEngine.isPlayingReplay,
    engine.triggerEngine?.briefingText != null,
)

