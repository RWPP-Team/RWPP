/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game

import com.corrodinggames.rts.gameFramework.GameEngineMapType
import io.github.rwpp.api.event.hooks.NativeEngineLifecycleCallbacks
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeEngineLifecycleMixin {
    @WrapMethod(method = "loadMap(ZZLcom/corrodinggames/rts/gameFramework/GameEngineMapType;)V", require = 1)
    fun wrapMapLoad(
        loadAi: Boolean,
        keepTeams: Boolean,
        mapType: GameEngineMapType,
        operation: Operation<Unit>,
    ) = NativeEngineLifecycleCallbacks.wrapMapLoad(loadAi, keepTeams, mapType) {
        operation.call(loadAi, keepTeams, mapType)
    }

    @AsmInject(
        method = "setActivityAndGameView(Landroid/app/Activity;Lcom/corrodinggames/rts/appFramework/GameViewInterface;Z)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun syncNativeMenuState(target: Any, activity: Any?, gameView: Any?, resumeWithMenu: Boolean) =
        NativeEngineLifecycleCallbacks.syncNativeMenuState(target, activity, gameView, resumeWithMenu)

    @AsmInject(method = "updateFrameInternal(FI)V", target = InjectionPoint.TAIL, require = 1)
    fun acknowledgeLoadedClient(target: Any, deltaTime: Float, deltaMillis: Int) =
        NativeEngineLifecycleCallbacks.acknowledgeClientReady(target)
}
