/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.event.events.ChatMessageEvent
import kotlinx.coroutines.flow.StateFlow
import io.github.rwpp.api.game.chat.ChatMute as ApiChatMute

/** Compose revision bridge over the shared, UI-independent room chat filter. */
internal object ChatMute {
    val revision: StateFlow<Long> get() = ApiChatMute.revision

    @Composable
    fun observeRevision(): Long = revision.collectAsState().value

    fun isMuted(player: Team): Boolean = ApiChatMute.isMuted(player)

    fun setMuted(player: Team, value: Boolean) = ApiChatMute.setMuted(player, value)

    fun clear() = ApiChatMute.clear()

    fun filters(event: ChatMessageEvent, roster: List<Team>): Boolean = ApiChatMute.filters(event, roster)
}
