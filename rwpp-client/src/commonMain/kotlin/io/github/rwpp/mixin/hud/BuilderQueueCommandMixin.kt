/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.hud

import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import io.github.rwpp.game.hotkeys.NativeBuilderGroupAssistance
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

/** Observes completed native commands without changing simulation or command application. */
@AsmMixin("com/corrodinggames/rts/gameFramework/CommandControllerCommand")
object BuilderQueueCommandMixin {
    @AsmInject(method = "issueCommand()V", target = InjectionPoint.TAIL, require = 1)
    fun applied(target: Any) = NativeBuilderGroupAssistance.onCommandApplied(target as CommandControllerCommand)
}
