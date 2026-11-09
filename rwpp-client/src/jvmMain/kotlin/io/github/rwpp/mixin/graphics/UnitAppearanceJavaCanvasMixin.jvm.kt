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
import com.corrodinggames.rts.java.JavaCanvas
import io.github.rwpp.graphics.UnitAppearanceRenderBridge
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** JavaCanvas is the desktop renderer; CanvasDirect hooks do not see these native draw calls. */
@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
object UnitAppearanceJavaCanvasMixin {
    private val replacing = ThreadLocal.withInitial { false }
    private inline fun replace(
        callback: CallbackInfo, texture: BitmapOrTexture, source: Rect? = null,
        draw: (UnitAppearanceRenderBridge.DrawOverride) -> Unit
    ) {
        if (replacing.get()) return
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source) ?: return
        replacing.set(true)
        try {
            draw(appearance)
        } finally {
            replacing.set(false)
        }
        callback.cancel()
    }

    @AsmInject(
        method = "drawImageRotated(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFFLandroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun rotated(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        angle: Float,
        paint: Paint?,
    ) {
        replace(callback, texture) { (self as JavaCanvas).drawImageRotated(it.texture, x, y, angle, paint) }
    }

    @AsmInject(
        method = "drawImageTopLeft(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun topLeft(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        paint: Paint?,
    ) {
        replace(callback, texture) { (self as JavaCanvas).drawImageTopLeft(it.texture, x, y, paint) }
    }

    @AsmInject(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun image(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        x: Float,
        y: Float,
        paint: Paint?,
    ) {
        replace(callback, texture) { (self as JavaCanvas).drawImage(it.texture, x, y, paint) }
    }

    @AsmInject(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun rect(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        source: Rect,
        destination: Rect,
        paint: Paint?,
    ) {
        replace(callback, texture, source) {
            (self as JavaCanvas).drawImage(
                it.texture,
                it.source ?: source,
                destination,
                paint
            )
        }
    }

    @AsmInject(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun rectF(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        source: Rect,
        destination: RectF,
        paint: Paint?,
    ) {
        replace(callback, texture, source) {
            (self as JavaCanvas).drawImage(
                it.texture,
                it.source ?: source,
                destination,
                paint
            )
        }
    }

    @AsmInject(
        method = "drawImageSoftware(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        target = InjectionPoint.HEAD, cancellable = true, require = 1,
    )
    fun software(
        callback: CallbackInfo, self: Any,
        texture: BitmapOrTexture,
        source: Rect,
        destination: Rect,
        paint: Paint?,
    ) {
        replace(callback, texture, source) {
            (self as JavaCanvas).drawImageSoftware(
                it.texture,
                it.source ?: source,
                destination,
                paint
            )
        }
    }
}
