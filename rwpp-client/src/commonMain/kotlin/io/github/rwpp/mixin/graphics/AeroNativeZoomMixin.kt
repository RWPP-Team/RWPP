/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.graphics.AeroNativeZoomPainter
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

/** Replaces artwork only; the game's hit rectangle, drag gesture and zoom limits stay native. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/BattleInterface")
object AeroNativeZoomMixin {
    @Redirect(
        method = "handleZoomControls(F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine.PENDING_drawImageRotatedClipped(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;FF)V"
        ), require = 1
    )
    fun zoom(
        graphics: GraphicsEngine, image: BitmapOrTexture, x: Float, y: Float,
        nativePaint: Paint, rotation: Float, scale: Float
    ) {
        AeroNativeZoomPainter.draw(graphics, x, y, scale, nativePaint.alpha >= 220)
    }
}
