/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.android

import android.util.Log
import io.github.rwpp.ui.UI
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** Sends kick/mod warnings to the extension UI rather than leaving them in the game log only. */
@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object AndroidGameWarningMixin {
    @AsmInject(
        method = "logWarningWithStack(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun showExtensionWarning(callbackInfo: CallbackInfo, message: String) {
        if (message.startsWith("Kicked") || message.startsWith("Missing")) {
            UI.showWarning(message, true)
            callbackInfo.cancel()
        }
    }
}

/** Keeps the legacy Android log tag while preserving the native game log path. */
@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object AndroidGameLogMixin {
    @AsmInject(
        method = "logInternal(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun forwardGameLog(message: String) {
        Log.i("RustedWarfare", message)
    }
}
