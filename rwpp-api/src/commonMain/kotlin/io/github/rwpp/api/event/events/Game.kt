/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.State

sealed class GameEvent : AbstractEvent()

class RefreshUIEvent : GameEvent()

/**
 * Event that is fired when the game is loaded.
 */
class GameLoadedEvent(val engine: GameEngine? = null) : GameEvent()

/** Event emitted when the application is leaving the game. */
class QuitGameEvent : GameEvent()

/** Event emitted when gameplay returns to the main menu. */
class ReturnMainMenuEvent : GameEvent()

/**
 * Event that is fired when the game is started.
 */
class StartGameEvent : GameEvent()

/**
 * Event that is fired when the game is resumed.
 */
class ResumeGameEvent(val engine: GameEngine? = null) : GameEvent()

/**
 * Event that is fired when hosting a multiplayer game.
 */
class HostGameEvent : GameEvent()

/**
 * Event that is fired when the local player starts hosting a singleplayer game.
 */
class HostSinglePlayerGameEvent : GameEvent()

/**
 * Event that is fired when the game is over.
 */
class GameOverEvent(val gameTime: Int, val winTeam: Int) : GameEvent()

/**
 * Event that is fired when the map changes.
 */
class MapChangedEvent(val mapName: String) : GameEvent()

class StateChangedEvent(val state: State) : GameEvent()

