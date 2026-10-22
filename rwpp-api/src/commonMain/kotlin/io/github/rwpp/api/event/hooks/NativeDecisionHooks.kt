/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
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
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkCallbacks")
object NativeJoinDecisionMixin {
    @AsmInject(method = "onPlayerJoining(Lcom/corrodinggames/rts/gameFramework/network/Connection;Ljava/lang/String;IILjava/lang/String;Lcom/corrodinggames/rts/game/Player;)Ljava/lang/String;", target = InjectionPoint.TAIL, require = 1)
    fun check(callback: CallbackInfo, target: Any, connection: Connection, name: String,
              protocolVersion: Int, gameVersion: Int, packageName: String?, player: Any?) {
        if (callback.getReturnValue<String>() != null) return
        val event = PlayerJoinAttemptEvent(
            connection = connection,
            name = name,
            protocolVersion = protocolVersion,
            gameVersion = gameVersion,
            packageName = packageName,
            team = player as? Team,
        )
        if (hasNativeEventListeners(PlayerJoinAttemptEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callback.setReturnValue(event.rejectionReason ?: "Rejected by an extension")
    }
}

/** Shared transport entry points. Product commands register callbacks instead of platform mixins. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeDecisionMixin {
    @AsmInject(method = "queueCommand(Lcom/corrodinggames/rts/gameFramework/CommandControllerCommand;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeQueue(callback: CallbackInfo, target: Any, command: CommandControllerCommand) {
        val event = CommandQueueEvent(command)
        if (hasNativeEventListeners(CommandQueueEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callback.cancel()
    }

    @AsmInject(method = "sendChatMessage(Ljava/lang/String;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeLocalChat(callback: CallbackInfo, target: Any, message: String) {
        val team = GameEngine.getInstance().networkEngine.localTeam
        val event = ChatMessageEvent(team?.name.orEmpty(), message, team, -1, ChatOrigin.LOCAL_SEND)
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callback.cancel()
    }

    @AsmInject(method = "routeChatMessageViaRelay(Lcom/corrodinggames/rts/gameFramework/network/Connection;Lcom/corrodinggames/rts/game/Team;Ljava/lang/String;Ljava/lang/String;Lcom/corrodinggames/rts/gameFramework/network/Connection;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeRelayChat(callback: CallbackInfo, target: Any, connection: Connection?, team: Team?, sender: String?, message: String?) {
        val event = ChatMessageEvent(sender.orEmpty(), message.orEmpty(), team, -1, ChatOrigin.REMOTE_RELAY, connection)
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callback.cancel()
    }

    @AsmInject(method = "receiveChatMessage(Lcom/corrodinggames/rts/gameFramework/network/Connection;ILjava/lang/String;Ljava/lang/String;)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun beforeReceive(callback: CallbackInfo, target: Any, connection: Connection?, color: Int, sender: String?, message: String?) {
        val team = connection?.player as? Team
        val event = ChatMessageEvent(sender.orEmpty(), message.orEmpty(), team, color, ChatOrigin.RECEIVE, connection)
        if (hasNativeEventListeners(ChatMessageEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callback.cancel()
    }
}
