/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.chat

import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Room-scoped receive filtering. Player identity uses object identity, never a reusable slot alone. */
object ChatMute {
    private val lock = Any()
    private val mutedPlayers = mutableListOf<Team>()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun isMuted(player: Team): Boolean = synchronized(lock) {
        mutedPlayers.any { it === player }
    }

    fun setMuted(player: Team, muted: Boolean) {
        synchronized(lock) {
            val index = mutedPlayers.indexOfFirst { it === player }
            if (muted && index < 0) mutedPlayers.add(player)
            else if (!muted && index >= 0) mutedPlayers.removeAt(index)
            _revision.value = _revision.value + 1L
        }
    }

    fun clear() = synchronized(lock) {
        mutedPlayers.clear()
        _revision.value = _revision.value + 1L
    }

    fun filters(event: ChatMessageEvent, roster: List<Team>): Boolean {
        if (event.origin != ChatOrigin.RECEIVE) return false
        // Native receive packets carry the sender slot in color. Connection identity is often null
        // on clients or points at the relay, so it cannot reliably identify the sender.
        val player = roster.singleOrNull { event.color >= 0 && it.teamId == event.color }
            ?: event.player?.takeIf { it.name == event.sender && event.sender.isNotBlank() }
            ?: roster.singleOrNull { it.name == event.sender && event.sender.isNotBlank() }
            ?: return false
        return isMuted(player)
    }
}
