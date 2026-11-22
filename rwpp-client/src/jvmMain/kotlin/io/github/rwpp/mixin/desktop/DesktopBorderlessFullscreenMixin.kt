/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.desktop

import com.corrodinggames.rts.appFramework.GameViewInterface
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyArg
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapOperation
import org.lwjgl.opengl.Display
import org.newdawn.slick.AppGameContainer
import java.util.concurrent.atomic.AtomicBoolean

private val fullscreenRequestedAtLaunch = AtomicBoolean()

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object DesktopMouseEdgeScrollMixin {
    @WrapOperation(
        method = "update(F)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.appFramework.GameViewInterface.isFullscreen()Z",
        ),
        require = 1,
    )
    fun allowFullscreenEdgeScroll(view: GameViewInterface, original: Operation<Boolean>): Boolean =
        original.call(view) && (appKoin.getOrNull<Settings>()?.mouseMoveView ?: true)
}

/** The launcher creates the Slick display before the engine loads its window preferences. */
@AsmMixin("com/corrodinggames/rts/java/MainLauncher")
object DesktopFullscreenLaunchMixin {
    @ModifyArg(
        method = "parseArguments([Ljava/lang/String;)V",
        index = 3,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.java.SlickGameContainer.<init>(Lorg/newdawn/slick/Game;IIZ)V",
        ),
        require = 2,
    )
    fun deferFullscreenUntilResolution(fullscreen: Boolean): Boolean {
        fullscreenRequestedAtLaunch.set(fullscreen)
        return false
    }
}

/** Use Slick's native borderless branch so the AWT input window can take focus. */
@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object DesktopBorderlessFullscreenMixin {
    private val wasUndecorated = ThreadLocal.withInitial { false }

    @AsmInject(method = "applyResolution()V", target = InjectionPoint.HEAD, require = 1)
    fun useBorderlessFullscreen(callback: CallbackInfo, self: Any) {
        wasUndecorated.set(java.lang.Boolean.getBoolean("org.lwjgl.opengl.Window.undecorated"))
        val settings = runCatching { GameEngine.getInstance()?.settings }.getOrNull() ?: return
        val requestedAtLaunch = fullscreenRequestedAtLaunch.getAndSet(false)
        // Older preferences can select either flag. The desktop toggle writes both together.
        val fullscreen = requestedAtLaunch || settings.slick2dFullScreen || settings.slick2dBorderless
        settings.slick2dFullScreen = fullscreen
        settings.slick2dBorderless = fullscreen
        if (fullscreen) settings.slick2dFullScreenResolution = "native"
    }

    @ModifyArg(
        method = "applyResolution()V",
        ordinal = 1,
        index = 2,
        at = At(InjectionPoint.INVOKE, target = "com.corrodinggames.rts.java.SlickGameContainer.setDisplayMode(IIZ)V"),
        require = 1,
    )
    fun avoidExclusiveFullscreen(@Suppress("UNUSED_PARAMETER") fullscreen: Boolean): Boolean = false

    @WrapOperation(
        method = "applyResolution()V",
        ordinal = 0,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.java.SlickGameContainer.setDisplayMode(IIZ)V",
        ),
        require = 1,
    )
    fun applyBorderlessWindow(
        container: AppGameContainer, width: Int, height: Int, fullscreen: Boolean, original: Operation<Unit>,
    ) {
        // Slick skips equal-size windowed modes. LWJGL applies decoration when it creates the window.
        if (!wasUndecorated.get() && Display.isCreated() && !container.isFullscreen &&
            container.width == width && container.height == height
        ) {
            Display.setDisplayMode(Display.getDisplayMode())
        }
        original.call(container, width, height, fullscreen)
    }
}
