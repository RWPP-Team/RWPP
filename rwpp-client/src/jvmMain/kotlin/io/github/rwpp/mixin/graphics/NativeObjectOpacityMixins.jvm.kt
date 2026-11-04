/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.Color
import android.graphics.Paint
import com.corrodinggames.rts.gameFramework.render.UniquePaint
import io.github.rwpp.graphics.*
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
@AddInterface("io/github/rwpp/graphics/DesktopPaintStateAccess")
abstract class DesktopPaintStateAccessMixin {
    @Accessor("currentPaint")
    abstract fun rwppCurrentPaint(value: Paint?)
}

/** The native renderer caches UniquePaint by identity, so invalidate before reusing our copy. */
@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
object DesktopObjectOpacityMixin {
    private class PaintPool {
        val copies = arrayOfNulls<UniquePaint>(8)
        var cursor = 0
        fun next(): UniquePaint {
            val slot = cursor
            cursor = (cursor + 1) % copies.size
            return copies[slot] ?: UniquePaint().also { copies[slot] = it }
        }
    }

    private val pools = ThreadLocal.withInitial { PaintPool() }

    @AsmInject(
        method = "applyPaintState(Landroid/graphics/Paint;ZLjava/lang/String;Lcom/corrodinggames/rts/java/JavaBitmap;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V",
        target = InjectionPoint.HEAD, require = 1
    )
    fun invalidate(callback: CallbackInfo, self: Any) {
        if (NativeObjectOpacity.current() < 1f) (self as DesktopPaintStateAccess).rwppCurrentPaint(null)
    }

    private fun opacity(paint: Paint?): Paint? {
        if (NativeObjectOpacity.current() >= 1f) return paint
        val copy = pools.get()!!.next()
        if (paint == null) {
            copy.reset(); copy.color = Color.WHITE
        } else copy.set(paint)
        copy.setShader((paint as? UniquePaint)?.shader)
        NativeObjectOpacity.adjust(copy)
        return copy
    }

    @ModifyArg(
        method = "applyPaint(Landroid/graphics/Paint;)V", index = 0, at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/java/JavaCanvas.applyPaintState(Landroid/graphics/Paint;ZLjava/lang/String;Lcom/corrodinggames/rts/java/JavaBitmap;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V"
        ), require = 1
    )
    fun paint(paint: Paint?): Paint? = opacity(paint)

    @ModifyArg(
        method = "applyBitmapPaint(Landroid/graphics/Paint;Lcom/corrodinggames/rts/java/JavaBitmap;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V",
        index = 0,
        at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/java/JavaCanvas.applyPaintState(Landroid/graphics/Paint;ZLjava/lang/String;Lcom/corrodinggames/rts/java/JavaBitmap;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V"
        ),
        require = 1
    )
    fun bitmap(paint: Paint?): Paint? = opacity(paint)

    @ModifyArg(
        method = "applyTextPaint(Landroid/graphics/Paint;Ljava/lang/String;)V", index = 0, at = At(
            InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/java/JavaCanvas.applyPaintState(Landroid/graphics/Paint;ZLjava/lang/String;Lcom/corrodinggames/rts/java/JavaBitmap;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V"
        ), require = 1
    )
    fun text(paint: Paint?): Paint? = opacity(paint)
}
