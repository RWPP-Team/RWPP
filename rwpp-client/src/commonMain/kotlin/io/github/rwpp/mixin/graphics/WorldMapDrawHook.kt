/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import io.github.rwpp.graphics.drawNativeMap
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect
import com.corrodinggames.rts.game.map.Map as NativeMap

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object WorldWaterSurfaceDrawMixin {
    @Redirect(
        method = "drawGame(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/game/map/Map.drawMap(F)V"
        ),
        require = 1
    )
    fun drawMapAndWaterSurfaces(map: NativeMap, frameDelta: Float) {
        drawNativeMap(map, frameDelta)
    }
}


