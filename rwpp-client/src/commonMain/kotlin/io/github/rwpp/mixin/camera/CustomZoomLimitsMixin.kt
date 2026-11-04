/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.camera

import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyReturnValue

/** The native gesture and mouse logic remains in charge of applying these bounds. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object CustomZoomLimitsMixin {
    @ModifyReturnValue(method = "getMaxZoomIn()F", require = 1)
    fun minimum(native: Float): Float = GameVisualSettings.get()?.let {
        if (it.customZoomLimits) it.minimumCameraZoom.coerceIn(0.05f, 1f) else native
    } ?: native

    @ModifyReturnValue(method = "getMaxZoomOut()F", require = 1)
    fun maximum(native: Float): Float = GameVisualSettings.get()?.let {
        if (it.customZoomLimits) it.maximumCameraZoom.coerceIn(1f, 10f) else native
    } ?: native
}
