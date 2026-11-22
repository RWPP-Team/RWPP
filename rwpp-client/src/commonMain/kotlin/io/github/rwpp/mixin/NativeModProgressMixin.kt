package io.github.rwpp.mixin

import io.github.rwpp.game.NativeModLoadProgress
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object NativeModProgressMixin {
    @AsmInject(method = "setLoadingMessage(Ljava/lang/String;Z)V", target = InjectionPoint.TAIL, require = 1)
    fun modProgress(target: Any, message: String?, fromEngine: Boolean) = NativeModLoadProgress.report(message)
}
