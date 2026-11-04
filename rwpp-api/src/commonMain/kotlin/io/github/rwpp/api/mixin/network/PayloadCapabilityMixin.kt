/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.network

import io.github.rwpp.api.net.impl.clearNativePayloadCapabilities
import io.github.rwpp.api.net.impl.forgetNativePayloadCapabilities
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** Drops negotiated connection capability state at native session teardown points. */
@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object PayloadCapabilityMixin {
    @AsmInject(
        method = "removeConnection(Lcom/corrodinggames/rts/gameFramework/network/Connection;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun forgetPayloadCapabilities(
        @Suppress("UNUSED_PARAMETER") callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        connection: Any,
    ) = forgetNativePayloadCapabilities(connection)

    @AsmInject(
        method = "disconnect(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun clearPayloadCapabilities(
        @Suppress("UNUSED_PARAMETER") callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") reason: String,
    ) = clearNativePayloadCapabilities()
}
