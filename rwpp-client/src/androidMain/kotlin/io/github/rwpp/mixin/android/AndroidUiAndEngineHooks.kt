/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.android

import android.util.Log
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.logic.hud.NativeHudVisibility
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.ANDROID_SETTINGS_OPTION
import io.github.rwpp.ui.openAndroidAeroSettings
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyExpressionValue

/** Both the native HUD menu and the Android menu dispatch through this game entry point. */
@AsmMixin("com/corrodinggames/rts/appFramework/GameActivity")
object AndroidAeroSettingsMixin {
    @AsmInject(method = "selectMenuOption(I)V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun openAeroSettings(callbackInfo: CallbackInfo, self: Any, option: Int) {
        if (option == ANDROID_SETTINGS_OPTION && openAndroidAeroSettings()) callbackInfo.cancel()
    }
}

/** Suppress only the native top-left counter. The FPS setting still drives Aero telemetry. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object AndroidNativeFpsHudMixin {
    @ModifyExpressionValue(
        method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(InjectionPoint.FIELD, target = "showFps:Z"), require = 1,
    )
    fun worldFps(enabled: Boolean): Boolean = enabled && !NativeHudVisibility.hidden
}

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
