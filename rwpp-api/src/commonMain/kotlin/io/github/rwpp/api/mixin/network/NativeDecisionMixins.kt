/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import io.github.rwpp.api.event.hooks.NativeDecisionCallbacks
import io.github.rwpp.api.event.hooks.NativeJoinDecisionCallbacks
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkCallbacks")
object NativeJoinDecisionMixin {
    @AsmInject(
        method = "onPlayerJoining(Lcom/corrodinggames/rts/gameFramework/network/Connection;Ljava/lang/String;IILjava/lang/String;Lcom/corrodinggames/rts/game/Player;)Ljava/lang/String;",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun check(
        callback: CallbackInfo,
        target: Any,
        connection: Any,
        name: String,
        protocolVersion: Int,
        gameVersion: Int,
        packageName: String?,
        player: Any?,
    ) {
        if (callback.getReturnValue<String>() != null) return
        NativeJoinDecisionCallbacks.check(connection, name, protocolVersion, gameVersion, packageName, player)
            ?.let { rejection -> callback.setReturnValue(rejection) }
    }
}

/** Native injection entry points; event policy remains in the event hook callbacks. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeDecisionMixin {
    @AsmInject(
        method = "queueCommand(Lcom/corrodinggames/rts/gameFramework/CommandControllerCommand;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun beforeQueue(callback: CallbackInfo, target: Any, command: Any) {
        if (NativeDecisionCallbacks.beforeQueue(target, command)) callback.cancel()
    }

    @AsmInject(
        method = "sendChatMessage(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1
    )
    fun beforeLocalChat(callback: CallbackInfo, target: Any, message: String) {
        if (NativeDecisionCallbacks.beforeLocalChat(target, message)) callback.cancel()
    }

    @AsmInject(
        method = "routeChatMessageViaRelay(Lcom/corrodinggames/rts/gameFramework/network/Connection;Lcom/corrodinggames/rts/game/Team;Ljava/lang/String;Ljava/lang/String;Lcom/corrodinggames/rts/gameFramework/network/Connection;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun beforeRelayChat(
        callback: CallbackInfo,
        target: Any,
        connection: Any?,
        team: Any?,
        sender: String?,
        message: String?
    ) {
        if (NativeDecisionCallbacks.beforeRelayChat(target, connection, team, sender, message)) callback.cancel()
    }

    @AsmInject(
        method = "receiveChatMessage(Lcom/corrodinggames/rts/gameFramework/network/Connection;ILjava/lang/String;Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun beforeReceive(
        callback: CallbackInfo,
        target: Any,
        connection: Any?,
        color: Int,
        sender: String?,
        message: String?
    ) {
        if (NativeDecisionCallbacks.beforeReceive(target, connection, color, sender, message)) callback.cancel()
    }
}
