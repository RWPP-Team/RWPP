/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game

import com.corrodinggames.rts.gameFramework.network.PasswordPrompt
import io.github.rwpp.api.game.impl.GamePlatform
import io.github.rwpp.api.game.impl.NativeGameLifecycle

/** UI extensions present native questions and return answers without replacing the native protocol. */
object NativeJoinPrompts {
    fun opened(prompt: PasswordPrompt) {
        if (prompt.respondViaPacket || prompt.question == null) NativeGameLifecycle.onJoinPromptOpened(prompt)
    }

    /** Invoke from the game dispatcher, just like the native submit/cancel callbacks. */
    fun respond(prompt: PasswordPrompt, input: String?) {
        try {
            if (input == null) {
                if (prompt.respondViaPacket || prompt.question == null) GamePlatform.cancelJoinServer()
                prompt.cancelAndDisconnect()
            } else {
                prompt.submitInput(input)
            }
        } finally {
            NativeGameLifecycle.onJoinPromptClosed(prompt)
        }
    }
}
