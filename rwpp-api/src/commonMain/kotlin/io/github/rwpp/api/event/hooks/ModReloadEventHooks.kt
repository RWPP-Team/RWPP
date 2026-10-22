/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.event.events.ModReloadFailedEvent
import com.corrodinggames.rts.gameFramework.GameEngine
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.WrapMethod
import kim.der.asm.api.annotation.Operation
import java.lang.reflect.InvocationTargetException

@AsmMixin("com/corrodinggames/rts/gameFramework/mod/ModManager")
object ModReloadEventHooks {
    @WrapMethod(method = "reloadModDataInternal(ZZ)V", require = 1)
    fun reload(preserveMissing: Boolean, metadataOnly: Boolean, original: Operation<Any?>) {
        val manager = GameEngine.getInstance().modManager
        GameEventPublisher.reloadMod(manager, preserveMissing, metadataOnly)
        try {
            original.call(preserveMissing, metadataOnly)
        } catch (failure: Throwable) {
            val error = if (failure is InvocationTargetException) failure.targetException else failure
            try {
                if (hasNativeEventListeners(ModReloadFailedEvent::class)) {
                    broadcastNativeEvent(ModReloadFailedEvent(manager, preserveMissing, metadataOnly, error))
                }
            } catch (notificationFailure: Throwable) {
                if (notificationFailure !== error) error.addSuppressed(notificationFailure)
            }
            throw error
        }
        GameEventPublisher.reloadModFinished(manager, preserveMissing, metadataOnly)
    }
}
