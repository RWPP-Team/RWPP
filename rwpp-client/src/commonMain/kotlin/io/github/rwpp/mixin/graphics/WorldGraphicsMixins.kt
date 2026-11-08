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
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.game.replay.withAeroPresentationTeam
import io.github.rwpp.graphics.NativeWorldPresentation
import io.github.rwpp.graphics.WorldGraphicsEnhancements
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

/** Restore native world flags before drawAll moves on to the GUI pass. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object WorldGraphicsDrawMixin {
    @Redirect(
        method = "drawAll(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/game/RWGameEngine.drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V"
        ),
        require = 1
    )
    fun drawWorld(self: Any, canvas: Any?, frameDelta: Float) {
        withAeroPresentationTeam(self as GameEngine) {
            WorldGraphicsEnhancements.withEnhancedWorldDraw(self, canvas, frameDelta)
            io.github.rwpp.ui.flushPendingHudFrame()
            NativeWorldPresentation.capture(self)
            io.github.rwpp.ui.drawNativeWorldCanvases(self)
        }
    }
}

/** Filtering is limited to world map tiles; unit filtering is enabled through renderAntiAlias. */
@AsmMixin("com/corrodinggames/rts/game/map/MapLayer")
object WorldMapTextureFilterMixin {
    @Redirect(
        method = "draw(Lcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;FFFFFFFFZZZ)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine.drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V"
        ),
        require = 1
    )
    fun drawMapTile(
        graphics: GraphicsEngine,
        texture: BitmapOrTexture,
        source: Rect,
        destination: RectF,
        paint: Paint
    ) {
        WorldGraphicsEnhancements.drawFilteredMapTile(graphics, texture, source, destination, paint)
    }
}
