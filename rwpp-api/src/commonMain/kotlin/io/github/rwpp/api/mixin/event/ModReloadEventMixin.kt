/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import io.github.rwpp.api.event.hooks.ModReloadCallbacks
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod

@AsmMixin("com/corrodinggames/rts/gameFramework/mod/ModManager")
object ModReloadEventHooks {
    @WrapMethod(method = "reloadModDataInternal(ZZ)V", require = 1)
    fun reload(preserveMissing: Boolean, metadataOnly: Boolean, original: Operation<Any?>) {
        ModReloadCallbacks.reload(preserveMissing, metadataOnly) {
            original.call(preserveMissing, metadataOnly)
        }
    }
}
