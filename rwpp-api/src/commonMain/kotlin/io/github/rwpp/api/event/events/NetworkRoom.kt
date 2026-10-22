/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.GameSetup
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.api.event.AbstractEvent

/** A native transport connection was established. */
data class TransportConnectedEvent(val network: NetworkEngine, val address: String?) : AbstractEvent()

/** The local player joined a game after native room setup completed. */
class JoinGameEvent(
    val address: String,
    val network: NetworkEngine? = null,
    val connection: Connection? = null,
    val team: Team? = null,
) : GameEvent()

/** The local player disconnected from the active game session. */
class DisconnectEvent(val reason: String, val network: NetworkEngine? = null) : GameEvent()

/** The native room returned to its battleroom state. */
data class RoomReturnedEvent(val network: NetworkEngine) : AbstractEvent()

/** Server-side setup data was applied to the room. */
data class RoomSetupAppliedEvent(val network: NetworkEngine, val setup: GameSetup) : AbstractEvent()

/** The game is checking the local mod list against requirements supplied by the server. */
class ModCheckEvent(val requiredMods: List<String>) : GameEvent() {
    override val propagateListenerFailures: Boolean
        get() = true
}

/** Native login validation before the server accepts a player. */
class PlayerJoinAttemptEvent(
    val connection: Connection,
    val name: String,
    val protocolVersion: Int,
    val gameVersion: Int,
    val packageName: String?,
    val team: Team?,
) : GameEvent() {
    var rejectionReason: String? = null

    override val propagateListenerFailures: Boolean
        get() = true
}
