/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.desktop

import io.github.rwpp.ui.DesktopComposeRuntime
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/java/SlickMainGame")
object DesktopComposeMixin {
    @AsmInject(
        method = "render(Lorg/newdawn/slick/GameContainer;Lorg/newdawn/slick/Graphics;)V",
        target = InjectionPoint.RETURN,
        require = 1,
    )
    fun renderCompose(callback: CallbackInfo, self: Any, container: Any, graphics: Any) {
        DesktopComposeRuntime.render(self, container)
    }

    @AsmInject(method = "closeRequested()Z", target = InjectionPoint.HEAD, require = 1)
    fun closeComposeWindow() {
        if (DesktopComposeRuntime.isAttached()) {
            io.github.rwpp.appKoin.get<io.github.rwpp.AppContext>().exit()
        }
    }
}

/** Retain native scripts and game rendering, but let Compose own menu drawing and input. */
@AsmMixin("com/corrodinggames/librocket/CommonLibRocket")
object DesktopComposeMenuMixin {
    @ModifyReturnValue(method = "isIdle()Z", require = 1)
    fun hideNativeMenus(result: Boolean): Boolean = DesktopComposeRuntime.isAttached() || result

    @AsmInject(method = "postUpdate()V", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun skipDeferredNativeDrawing(callback: CallbackInfo) {
        if (DesktopComposeRuntime.isAttached()) callback.cancel()
    }
}

/** setDocument is declared by LibRocket itself and inherited by CommonLibRocket. */
@AsmMixin("com/LibRocket")
object DesktopComposeDocumentMixin {
    @AsmInject(
        method = "setDocument(Ljava/lang/String;Ljava/util/HashMap;Z)Lcom/ElementDocument;",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun suppressReplacedNativeScreens(callback: CallbackInfo) {
        if (DesktopComposeRuntime.isAttached()) callback.setReturnValue(null)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object DesktopComposePauseMixin {
    @AsmInject(method = "handleTopButtons(FZ)V", target = InjectionPoint.TAIL, require = 1)
    fun openComposePauseMenu() {
        if (!DesktopComposeRuntime.isAttached()) return
        val interfaceEngine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance().interfaceEngine
        if (interfaceEngine.pauseMenuOpen) {
            interfaceEngine.pauseMenuOpen = false
            io.github.rwpp.appKoin.get<io.github.rwpp.ui.GameScreen>().showPauseMenu = true
        }
    }
}
