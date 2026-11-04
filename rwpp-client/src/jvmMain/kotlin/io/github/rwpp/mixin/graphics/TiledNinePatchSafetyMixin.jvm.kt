/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import android.graphics.Paint
import android.graphics.Rect
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsUtils
import io.github.rwpp.graphics.WorldGraphicsEnhancements
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect
import kotlin.math.ceil

/** Avoids the game's 2,000-patch abort when enhanced rendering exposes a very large tiled panel. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/elements/NinePatchStyledBox")
object TiledNinePatchSafetyMixin {
    @Redirect(
        method = "drawPatch(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Paint;IIIIFFFFZ)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsUtils.drawTiledImageScaled(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/Rect;Landroid/graphics/Paint;IIIIF)V"
        ),
        require = 1
    )
    fun drawTiledPatch(
        graphics: GraphicsEngine,
        texture: BitmapOrTexture,
        source: Rect,
        destination: Rect,
        paint: Paint,
        offsetX: Int,
        offsetY: Int,
        tileWidth: Int,
        tileHeight: Int,
        tileScaleBlend: Float,
    ) {
        if (WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled() &&
            exceedsNativeTileLimit(source, destination)
        ) {
            // Stretch only the center patch as a bounded fallback; the native tiled path would
            // abandon this patch after logging once per frame, leaving a partially drawn panel.
            graphics.drawImage(texture, source, destination, paint)
        } else {
            GraphicsUtils.drawTiledImageScaled(
                graphics,
                texture,
                source,
                destination,
                paint,
                offsetX,
                offsetY,
                tileWidth,
                tileHeight,
                tileScaleBlend,
            )
        }
    }

    private fun exceedsNativeTileLimit(source: Rect, destination: Rect): Boolean {
        val sourceWidth = source.width()
        val sourceHeight = source.height()
        val destinationWidth = destination.width().coerceAtLeast(0)
        val destinationHeight = destination.height().coerceAtLeast(0)
        if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth == 0 || destinationHeight == 0) return false

        // The game increments its guard counter for every x/y patch pair. Add a tile per axis for
        // fractional scaling so floating point stepping cannot cross the hard native limit.
        val columns = ceil(destinationWidth.toDouble() / sourceWidth).toLong() + 1L
        val rows = ceil(destinationHeight.toDouble() / sourceHeight).toLong() + 1L
        return columns > 2000L || rows > 2000L || columns * rows > 2000L
    }
}
