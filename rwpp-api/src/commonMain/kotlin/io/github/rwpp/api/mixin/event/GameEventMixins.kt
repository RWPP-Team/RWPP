/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import io.github.rwpp.api.event.hooks.*
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/Team")
object PlayerEventMixin {
    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.HEAD, require = 1)
    fun captureDefeatState(target: Any, deltaTime: Float) =
        PlayerEventCallbacks.captureDefeatState(target, deltaTime)

    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishDefeatAndGameOver(target: Any, deltaTime: Float) =
        PlayerEventCallbacks.publishDefeatAndGameOver(target, deltaTime)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/Connection")
object ClientEventMixin {
    @AsmInject(method = "handleRemoteDisconnect(ZZLjava/lang/String;)V", target = InjectionPoint.TAIL, require = 1)
    fun publishPlayerLeave(connection: Any, graceful: Boolean, timeout: Boolean, reason: String?) =
        ClientEventCallbacks.publishPlayerLeave(connection, graceful, timeout, reason)
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnitMetadata")
object UnitMetadataEventMixin {
    @AsmInject(
        method = "reportUnitMismatch(Lcom/corrodinggames/rts/game/units/custom/UnitMismatchInfo;Ljava/util/HashMap;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun publishModCheck(callbackInfo: CallbackInfo, mismatchInfo: Any, mismatches: HashMap<*, *>) {
        if (UnitMetadataEventCallbacks.publishModCheck(mismatches)) callbackInfo.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/trigger/TriggerActivator")
object MapTriggerEventMixin {
    @AsmInject(
        method = "activate(Lcom/corrodinggames/rts/gameFramework/trigger/TriggerEngine;Lcom/corrodinggames/rts/gameFramework/trigger/MapTrigger;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishTriggerActivation(triggerEngine: Any, trigger: Any) =
        MapTriggerEventCallbacks.publishTriggerActivation(triggerEngine, trigger)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkEventMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                    + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishRemotePlayerJoin(team: Any) = NetworkEventCallbacks.publishRemotePlayerJoin(team)

    @AsmInject(
        method = "addAIToGame()V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                    + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishAIPlayerJoin(team: Any) = NetworkEventCallbacks.publishAIPlayerJoin(team)
}

@AsmMixin("com/corrodinggames/rts/appFramework/MultiplayerBattleroomActivity")
object RefreshUiEventMixin {
    @AsmInject(method = "updateUI()V", target = InjectionPoint.TAIL, require = 1)
    fun publishRefreshUi() = RefreshUiEventCallbacks.publishRefreshUi()
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object EngineLoadedMixin {
    @AsmInject(method = "init(Landroid/content/Context;)V", target = InjectionPoint.TAIL, require = 1)
    fun publishGameLoaded(target: Any, context: Any) = EngineLoadedCallbacks.publishGameLoaded(target, context)
}
