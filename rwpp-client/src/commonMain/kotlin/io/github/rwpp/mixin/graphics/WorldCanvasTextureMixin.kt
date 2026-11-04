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
import io.github.rwpp.graphics.*
import kim.der.asm.api.annotation.*

/** Final Canvas sampling covers cached terrain, units and buildings, not just MapLayer source tiles. */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/CanvasDirect")
object WorldCanvasTextureMixin {
    private val shapePaint = ThreadLocal.withInitial { Paint() }
    private fun draw(original: Paint?, block: (Paint?) -> Unit) {
        val filtered = WorldTextureFrame.isFiltered()
        if (filtered || NativeObjectOpacity.current() < 1f) WorldFilteredPaints.draw(original, configure = {
            if (filtered) enableNativePaintFiltering(it)
            NativeObjectOpacity.adjust(it)
        }, block = block) else block(original)
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;FFLandroid/graphics/Paint;)V",
        require = 1
    )
    fun image(texture: BitmapOrTexture, x: Float, y: Float, original: Paint?, operation: Operation<Unit>) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture)
        draw(original) { operation.call(appearance?.texture ?: texture, x, y, it) }
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;)V",
        require = 1
    )
    fun rect(texture: BitmapOrTexture, source: Rect, destination: Rect, original: Paint?, operation: Operation<Unit>) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        draw(original) { operation.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, it) }
    }

    @WrapMethod(
        method = "drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V",
        require = 1
    )
    fun rectF(
        texture: BitmapOrTexture,
        source: Rect,
        destination: RectF,
        original: Paint?,
        operation: Operation<Unit>
    ) {
        val appearance = UnitAppearanceRenderBridge.drawOverride(texture, source)
        draw(original) {
            operation.call(appearance?.texture ?: texture, appearance?.source ?: source, destination, it)
        }
    }

    private fun shape(original: Paint): Paint {
        val ringColor = UnitAppearanceRenderBridge.selectionRingColor()
        if (ringColor == null && NativeObjectOpacity.current() >= 1f) return original
        return shapePaint.get()!!.also { copy ->
            copy.set(original)
            ringColor?.let(copy::setColor)
            NativeObjectOpacity.adjust(copy)
        }
    }

    @ModifyArg(
        method = "drawCircle(FFFLandroid/graphics/Paint;)V", index = 3, at = At(
            InjectionPoint.INVOKE, target = "android/graphics/Canvas.drawCircle(FFFLandroid/graphics/Paint;)V"
        ), require = 1
    )
    fun circle(paint: Paint): Paint = shape(paint)

    @ModifyArg(
        method = "drawLine(FFFFLandroid/graphics/Paint;)V", index = 4, at = At(
            InjectionPoint.INVOKE, target = "android/graphics/Canvas.drawLine(FFFFLandroid/graphics/Paint;)V"
        ), require = 1
    )
    fun line(paint: Paint): Paint = shape(paint)

    @ModifyArg(
        method = "drawLines([FIILandroid/graphics/Paint;)V", index = 3, at = At(
            InjectionPoint.INVOKE, target = "android/graphics/Canvas.drawLines([FIILandroid/graphics/Paint;)V"
        ), require = 1
    )
    fun lines(paint: Paint): Paint = shape(paint)

    @ModifyArg(
        method = "drawRect(Landroid/graphics/Rect;Landroid/graphics/Paint;)V", index = 1, at = At(
            InjectionPoint.INVOKE,
            target = "android/graphics/Canvas.drawRect(Landroid/graphics/Rect;Landroid/graphics/Paint;)V"
        ), require = 1
    )
    fun shapeRect(paint: Paint): Paint = shape(paint)

    @ModifyArg(
        method = "drawRect(Landroid/graphics/RectF;Landroid/graphics/Paint;)V", index = 1, at = At(
            InjectionPoint.INVOKE,
            target = "android/graphics/Canvas.drawRect(Landroid/graphics/RectF;Landroid/graphics/Paint;)V"
        ), require = 1
    )
    fun shapeRectF(paint: Paint): Paint = shape(paint)

    @ModifyArg(
        method = "drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V", index = 3, at = At(
            InjectionPoint.INVOKE,
            target = "android/graphics/Canvas.drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V"
        ), require = 1
    )
    fun text(paint: Paint): Paint = shape(paint)
}
