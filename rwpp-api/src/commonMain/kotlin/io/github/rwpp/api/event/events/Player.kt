/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.network.Connection

sealed class PlayerEvent : AbstractEvent()

/**
 * Event that is fired when a player joins the game.
 */
class PlayerJoinEvent(val player: Team, val connection: Connection? = null) : PlayerEvent()

/**
 * Event that is fired when a player leaves the game.
 */
class PlayerLeaveEvent(
    val player: Team?,
    val reason: String,
    val connection: Connection? = null,
) : PlayerEvent()

/**
 * Event that is fired when a player is defeated.
 */
class PlayerDefeatedEvent(val player: Team) : PlayerEvent()
