/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.network.Connection

/** Where a native chat message is being processed. */
enum class ChatOrigin {
    LOCAL_SEND,
    REMOTE_RELAY,
    RECEIVE,
}

/** A chat message submitted or received through the native room. */
class ChatMessageEvent(
    var sender: String,
    var message: String,
    val player: Team?,
    var color: Int,
    val origin: ChatOrigin = ChatOrigin.RECEIVE,
    val connection: Connection? = null,
) : GameEvent() {
    override val propagateListenerFailures: Boolean
        get() = true
}

/** A system message sent by the native room. */
class SystemMessageEvent(val message: String) : GameEvent()
