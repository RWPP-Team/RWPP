/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import io.github.rwpp.api.event.events.CommandQueueEvent
import io.github.rwpp.api.event.events.PlayerJoinAttemptEvent

internal object NativeJoinDecisionCallbacks {
    fun check(
        connection: Any, name: String,
        protocolVersion: Int, gameVersion: Int, packageName: String?, player: Any?
    ): String? {
        val event = PlayerJoinAttemptEvent(
            connection = connection as Connection,
            name = name,
            protocolVersion = protocolVersion,
            gameVersion = gameVersion,
            packageName = packageName,
            team = player as? Team,
        )
        if (hasNativeEventListeners(PlayerJoinAttemptEvent::class)) broadcastNativeEvent(event)
        return if (event.isCancelled) event.rejectionReason ?: "Rejected by an extension" else null
    }
}

/** Shared transport entry points. Product commands register callbacks instead of platform mixins. */
internal object NativeDecisionCallbacks {
    fun beforeQueue(target: Any, command: Any): Boolean {
        val event = CommandQueueEvent(command as CommandControllerCommand)
        if (hasNativeEventListeners(CommandQueueEvent::class)) broadcastNativeEvent(event)
        return event.isCancelled
    }

    fun beforeLocalChat(target: Any, message: String): Boolean {
        val team = GameEngine.getInstance().networkEngine.localTeam
        val event = ChatMessageEvent(team?.name.orEmpty(), message, team, -1, ChatOrigin.LOCAL_SEND)
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        return event.isCancelled
    }

    fun beforeRelayChat(target: Any, connection: Any?, team: Any?, sender: String?, message: String?): Boolean {
        val event = ChatMessageEvent(
            sender.orEmpty(),
            message.orEmpty(),
            team as? Team,
            -1,
            ChatOrigin.REMOTE_RELAY,
            connection as? Connection
        )
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        return event.isCancelled
    }

    fun beforeReceive(target: Any, connection: Any?, color: Int, sender: String?, message: String?): Boolean {
        val nativeConnection = connection as? Connection
        val team = nativeConnection?.player as? Team
        val event =
            ChatMessageEvent(sender.orEmpty(), message.orEmpty(), team, color, ChatOrigin.RECEIVE, nativeConnection)
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        return event.isCancelled
    }
}
