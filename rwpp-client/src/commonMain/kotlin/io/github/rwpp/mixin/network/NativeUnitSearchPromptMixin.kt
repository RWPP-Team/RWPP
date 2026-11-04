/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.network

import com.corrodinggames.rts.gameFramework.network.PasswordPrompt
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.NativeJoinPrompts
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.ui.UI
import io.github.rwpp.utils.isNativeUnitSearchPrompt
import io.github.rwpp.utils.nativePromptText
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kotlinx.coroutines.launch

/** Every native prompt uses one extension-owned surface and retains the original protocol callback. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NativeUnitSearchPromptMixin {
    @AsmInject(
        method = "showPasswordPrompt(Lcom/corrodinggames/rts/gameFramework/network/PasswordPrompt;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun search(callback: CallbackInfo, prompt: PasswordPrompt) {
        val search = isNativeUnitSearchPrompt(prompt.question)
        val title = if (search) readI18n("rwpp.ingame.searchUnits") else
            prompt.titleOverride?.takeIf(String::isNotBlank) ?: readI18n(
                if (prompt.question != null) "rwpp.dialog.serverQuestion" else "rwpp.dialog.passwordRequired"
            )
        val message = if (search) readI18n("rwpp.ingame.searchUnitsPrompt") else
            nativePromptText(prompt.question) { readI18n(it) } ?: readI18n("rwpp.dialog.passwordPrompt")
        NativeJoinPrompts.opened(prompt)
        UI.showQuestion(title, message, allowEmptyReply = true) { input ->
            GameScope.launch {
                NativeJoinPrompts.respond(prompt, input)
            }
        }
        callback.cancel()
    }
}
