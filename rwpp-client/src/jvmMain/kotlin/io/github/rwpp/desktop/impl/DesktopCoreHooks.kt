/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.game.Team
import android.app.Activity
import com.corrodinggames.rts.appFramework.GameViewInterface
import com.corrodinggames.librocket.scripts.ScriptEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.trigger.MapTrigger
import com.corrodinggames.rts.gameFramework.trigger.TriggerEngine
import javax.swing.SwingUtilities
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import io.github.rwpp.config.Settings

@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object GameEngineLogMixin {
    @AsmInject(
        method = "logInternal(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressBootstrapLog(callbackInfo: CallbackInfo, message: String) {
        if (message == "Fail safe menu" || message == "--- setRunning ---") callbackInfo.cancel()
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
        DesktopCoreHookActions.sendWelcomeIfEnabled(connection)
        callbackInfo.cancel()
    }
}

/**
 * Keep game classes out of mixin handler signatures. The ASM processor reflects those signatures
 * while the target class can still be in its defining call, so resolving native argument classes
 * there can recursively ask the host loader to define that target a second time.
 */
private object DesktopCoreHookActions {
    fun sendWelcomeIfEnabled(nativeConnection: Any) {
        if (io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>().showWelcomeMessage != true) return
        val connection = nativeConnection as Connection
        val output = com.corrodinggames.rts.gameFramework.network.OutputNetStream()
        output.writeUTF(io.github.rwpp.welcomeMessage)
        output.writeByte(3)
        output.writeNullableUTF("RWPP")
        output.writeConnectionId(null)
        output.writeInt(-1)
        val network = GameEngine.getInstance().networkEngine
        network.sendPacketOnConnection(connection, output.createPacket(141))
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
