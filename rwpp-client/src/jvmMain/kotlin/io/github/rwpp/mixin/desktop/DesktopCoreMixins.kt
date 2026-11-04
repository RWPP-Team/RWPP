/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.desktop

import io.github.rwpp.game.network.DesktopWelcomeMessage
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object GameEngineLogMixin {
    @AsmInject(
        method = "logInternal(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressKnownNoisyLog(callbackInfo: CallbackInfo, message: String) {
        if (
            message == "Fail safe menu" ||
            message == "--- setRunning ---" ||
            message == "tileImage hit limit"
        ) {
            callbackInfo.cancel()
        }
    }
}

@AsmMixin("com/corrodinggames/rts/java/MainLauncher")
object MainLauncherMixin {
    @AsmInject(
        method = "onPlayerJoined(Lcom/corrodinggames/rts/gameFramework/network/Connection;Ljava/lang/String;Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun sendWelcomeMessage(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") self: Any,
        connection: Any,
        @Suppress("UNUSED_PARAMETER") playerName: String?,
        @Suppress("UNUSED_PARAMETER") playerId: String?,
    ) {
        DesktopWelcomeMessage.sendIfEnabled(connection)
        callbackInfo.cancel()
    }
}

@AsmMixin("com/corrodinggames/librocket/scripts/ScriptEngine")
object ScriptThreadAccessMixin {
    @AsmInject(
        method = "checkThreadAccess()V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun allowComposeCallbacks(callbackInfo: CallbackInfo) {
        callbackInfo.cancel()
    }
}
