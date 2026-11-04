/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import io.github.rwpp.api.net.impl.dispatchNativeExtensionPacket
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** Native receive injection adapter for extension system packets. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkPacketMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun dispatchExtensionPacket(callbackInfo: CallbackInfo, target: Any, nativePacket: Any) {
        if (dispatchNativeExtensionPacket(target, nativePacket)) callbackInfo.cancel()
    }
}
