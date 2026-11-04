/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game

import io.github.rwpp.api.event.hooks.NativeReplayLifecycleCallbacks
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import java.io.File

@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
object NativeReplayLifecycleMixin {
    @WrapMethod(method = "loadReplayFile(Ljava/lang/String;Ljava/io/File;)Z", require = 1)
    fun wrapReplayLoad(replayName: String, file: File, operation: Operation<Boolean>): Boolean =
        NativeReplayLifecycleCallbacks.wrapReplayLoad(replayName, file) { name, replayFile ->
            operation.call(name, replayFile)
        }
}
