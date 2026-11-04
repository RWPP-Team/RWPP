/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.performance

import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyConstant

/** Android's native game thread uses these periods; keep native behavior when unset. */
@AsmMixin("com/corrodinggames/rts/gameFramework/GameThread")
object NativeFrameLimitMixin {
    @ModifyConstant(method = "run()V", constant = "32258064", require = 1)
    fun battery(native: Long): Long = GameVisualSettings.frameDuration(native)

    @ModifyConstant(method = "run()V", constant = "3333333", require = 1)
    fun high(native: Long): Long = GameVisualSettings.frameDuration(native)

    @ModifyConstant(method = "run()V", constant = "16393442", require = 1)
    fun normal(native: Long): Long = GameVisualSettings.frameDuration(native)
}
