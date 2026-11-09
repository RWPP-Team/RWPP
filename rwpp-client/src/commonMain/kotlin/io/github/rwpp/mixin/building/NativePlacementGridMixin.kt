/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.building

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.graphics.NativePlacementGrid
import io.github.rwpp.graphics.NativePlacementTile
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/** InterfaceEngine.update is the frame boundary for the native placement preview. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine")
object PlacementGridFrameMixin {
    @AsmInject(method = "update(F)V", target = InjectionPoint.HEAD, require = 1)
    fun beginFrame(callback: CallbackInfo, self: Any, delta: Float) {
        NativePlacementGrid.beginFrame()
    }
}

/** Captures the tile bounds the native game uses for its green placement squares. */
@AsmMixin("com/corrodinggames/rts/game/map/Map")
object NativePlacementGridMixin {
    @AsmInject(
        method = "drawBuildingPlacement(Lcom/corrodinggames/rts/game/units/OrderableUnit;IIIIIILcom/corrodinggames/rts/gameFramework/render/GraphicsEngine;ZI)V",
        target = InjectionPoint.HEAD,
        require = 1,
    )
    fun capture(
        callback: CallbackInfo,
        self: Any,
        unit: Any,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cameraX: Int,
        cameraY: Int,
        graphics: Any,
        filled: Boolean,
        placementCounter: Int,
    ) {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return
        val map = engine.map ?: return
        val stepX = map.tileWidth.toFloat()
        val stepY = map.tileHeight.toFloat()
        if (stepX <= 0f || stepY <= 0f) return
        NativePlacementGrid.record(
            NativePlacementTile(
                left * stepX,
                top * stepY,
                (right + 1) * stepX,
                (bottom + 1) * stepY,
                stepX,
                stepY,
            )
        )
    }
}
