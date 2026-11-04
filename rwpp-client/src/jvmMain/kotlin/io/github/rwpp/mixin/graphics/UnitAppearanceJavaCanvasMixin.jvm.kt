/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.graphics.UnitAppearanceRenderBridge
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod

/** JavaCanvas is the desktop renderer; CanvasDirect hooks do not see these native draw calls. */
@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
object UnitAppearanceJavaCanvasMixin {
    @WrapMethod(
        method = "drawImageRotated(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFFLandroid/graphics/Paint;)V",
        require = 1,
    )
    fun rotated(
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        angle: Float,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture)
        original.call(appearance?.texture ?: texture, x, y, angle, paint)
    }

    @WrapMethod(
        method = "drawImageTopLeft(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        require = 1,
    )
    fun topLeft(
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture)
        original.call(appearance?.texture ?: texture, x, y, paint)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        require = 1,
    )
    fun image(
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture)
        original.call(appearance?.texture ?: texture, x, y, paint)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        require = 1,
    )
    fun rect(
        texture: BitmapOrTexture,
        source: Rect,
        destination: Rect,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        original.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, paint)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        require = 1,
    )
    fun rectF(
        texture: BitmapOrTexture,
        source: Rect,
        destination: RectF,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        original.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, paint)
    }

    @WrapMethod(
        method = "drawImageSoftware(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        require = 1,
    )
    fun software(
        texture: BitmapOrTexture,
        source: Rect,
        destination: Rect,
        paint: Paint?,
        original: Operation<kotlin.Unit>,
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        original.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, paint)
    }
}
