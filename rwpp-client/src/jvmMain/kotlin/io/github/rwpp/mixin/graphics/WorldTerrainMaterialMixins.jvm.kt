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
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import io.github.rwpp.graphics.DesktopWorldTerrainMaterials
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/game/map/MapLayerBuffer")
object WorldTerrainCacheMaterialMixin {
    @Redirect(
        method = "updateAndDraw(F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/GraphicsEngine.drawImage(Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V"
        ), require = 1
    )
    fun drawTerrain(graphics: GraphicsEngine, texture: BitmapOrTexture, source: Rect, target: RectF, paint: Paint) {
        DesktopWorldTerrainMaterials.drawCachedTerrain(graphics, texture, source, target, paint)
    }
}

/** Normal map edits flow through MapLayer.setTile; mark only terrain layers used by the mask. */
@AsmMixin("com/corrodinggames/rts/game/map/MapLayer")
object WorldTerrainMutationMixin {
    @AsmInject(
        method = "setTile(IILcom/corrodinggames/rts/game/map/MapTile;Z)V",
        target = InjectionPoint.HEAD,
        require = 1
    )
    fun markTerrainMutation(callback: CallbackInfo, self: Any, x: Int, y: Int, tile: MapTile?, randomVariant: Boolean) {
        DesktopWorldTerrainMaterials.markTerrainTileChanged(self as MapLayer, x, y)
    }
}
