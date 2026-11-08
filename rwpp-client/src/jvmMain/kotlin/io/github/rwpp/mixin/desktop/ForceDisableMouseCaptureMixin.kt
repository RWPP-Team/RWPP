/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.desktop

import com.corrodinggames.rts.gameFramework.GameEngine
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Native mouse capture locks the pointer into the window and hides the system cursor. The Compose
 * host draws its own pointer and the settings screen no longer exposes the switch, so the field is
 * forced off before the native grab decision runs. A stale `true` from an older profile is cleared
 * by the next native settings save.
 */
@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object ForceDisableMouseCaptureMixin {
    @AsmInject(method = "updateContainerSettings()V", target = InjectionPoint.HEAD, require = 1)
    fun disableCapture(callback: CallbackInfo, self: Any) {
        val settings = runCatching { GameEngine.getInstance()?.settings }.getOrNull() ?: return
        settings.enableMouseCapture = false
    }
}
