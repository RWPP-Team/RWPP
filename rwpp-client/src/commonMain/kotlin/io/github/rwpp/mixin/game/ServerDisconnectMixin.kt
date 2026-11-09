/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.game

import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwpp.logic.ServerDisconnectNotice
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyReturnValue

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object ServerDisconnectMixin {
    @AsmInject(method = "updateClientConnectionStatus(F)V", target = InjectionPoint.TAIL, require = 1)
    fun showDisconnectNotice(self: Any, delta: Float) {
        ServerDisconnectNotice.update(self as NetworkEngine)
    }

    // Keep the local simulation stopped even after the player dismisses the notice/menu.
    // This does not send a pause command to other players or disconnect the room.
    @ModifyReturnValue(method = "shouldGameBePaused()Z", require = 1)
    fun pauseDisconnectedClient(result: Boolean): Boolean {
        val engine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance() ?: return result
        return result || ServerDisconnectNotice.isDisconnected(engine.networkEngine)
    }
}
