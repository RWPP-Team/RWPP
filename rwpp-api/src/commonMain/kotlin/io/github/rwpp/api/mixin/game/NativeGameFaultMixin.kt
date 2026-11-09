/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.impl.NativeGameFaults
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod

/** The native synchronized frame unwinds its locks before recovery handles the failure. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object NativeGameFaultMixin {
    @WrapMethod(method = "updateFrame(FI)V", require = 1)
    fun frame(delta: Float, millis: Int, original: Operation<Unit>) {
        val engine = GameEngine.getInstance()
        NativeGameFaults.runFrame(engine) { original.call(delta, millis) }
    }
}
