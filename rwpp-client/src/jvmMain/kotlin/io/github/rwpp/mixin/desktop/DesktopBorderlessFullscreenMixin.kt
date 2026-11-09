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
 * SlickMainGame.applyResolution selects LWJGL exclusive fullscreen when only
 * slick2dFullScreen is set. Exclusive mode surrenders the foreground as soon as
 * the AWT input-method window takes focus. Forcing the borderless branch keeps
 * the game on an undecorated window, the display model v1.6.2 used, so the
 * always-on-top input dialog can stay above it. The hook runs before the first
 * setDisplayMode call, so no runtime display switch or GL rebuild is needed.
 */
@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object DesktopBorderlessFullscreenMixin {
    @AsmInject(method = "applyResolution()V", target = InjectionPoint.HEAD, require = 1)
    fun useBorderlessFullscreen(callback: CallbackInfo, self: Any) {
        val settings = runCatching { GameEngine.getInstance()?.settings }.getOrNull() ?: return
        // applyResolution treats borderless as fullscreen (`if (borderless) fullscreen = true`),
        // so a stale true value keeps the game fullscreen after the immersive toggle is cleared.
        // The extension owns this field because the Compose settings screen is the only writer.
        settings.slick2dBorderless = settings.slick2dFullScreen
    }
}
