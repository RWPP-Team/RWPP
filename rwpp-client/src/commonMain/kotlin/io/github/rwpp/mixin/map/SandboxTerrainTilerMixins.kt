/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.map

import android.graphics.PointF
import com.corrodinggames.rts.game.map.MapLayer
import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.units.TerrainType
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.map.TerrainTilerWriteCapture
import kim.der.asm.api.annotation.*

private const val TERRAIN_TILER_CLASS = "com/corrodinggames/rts/game/units/TerrainTiler"
private const val TERRAIN_LAYER_SET_TILE =
    "com/corrodinggames/rts/game/map/MapLayer.setTile(IILcom/corrodinggames/rts/game/map/MapTile;Z)V"
private const val TERRAIN_POINT_METHOD =
    "a(Lcom/corrodinggames/rts/game/units/TerrainType;Landroid/graphics/PointF;)V"
private const val TERRAIN_EDGE_METHOD =
    "a(Lcom/corrodinggames/rts/game/units/TerrainType;Lcom/corrodinggames/rts/game/map/MapTile;IIIILjava/lang/String;)Z"
private const val TERRAIN_DETAILS_METHOD =
    "a(Lcom/corrodinggames/rts/game/units/TerrainType;Lcom/corrodinggames/rts/game/map/MapTile;Lcom/corrodinggames/rts/game/map/MapTile;II)V"

@AsmMixin(TERRAIN_TILER_CLASS, priority = 1100)
object SandboxTerrainTilerSetTileMixin {
    @WrapOperation(
        method = TERRAIN_POINT_METHOD,
        ordinal = -1,
        at = At(InjectionPoint.INVOKE, target = TERRAIN_LAYER_SET_TILE),
        require = 3,
    )
    fun recordPointMethodWrites(
        layer: MapLayer,
        x: Int,
        y: Int,
        tile: MapTile?,
        randomize: Boolean,
        original: Operation<Unit>,
    ) {
        original.call(layer, x, y, tile, randomize)
        TerrainTilerWriteCapture.record(layer, x, y)
    }

    @WrapOperation(
        method = TERRAIN_EDGE_METHOD,
        ordinal = -1,
        at = At(InjectionPoint.INVOKE, target = TERRAIN_LAYER_SET_TILE),
        require = 1,
    )
    fun recordEdgeMethodWrites(
        layer: MapLayer,
        x: Int,
        y: Int,
        tile: MapTile?,
        randomize: Boolean,
        original: Operation<Unit>,
    ) {
        original.call(layer, x, y, tile, randomize)
        TerrainTilerWriteCapture.record(layer, x, y)
    }

    @WrapOperation(
        method = TERRAIN_DETAILS_METHOD,
        ordinal = -1,
        at = At(InjectionPoint.INVOKE, target = TERRAIN_LAYER_SET_TILE),
        require = 7,
    )
    fun recordDetailsMethodWrites(
        layer: MapLayer,
        x: Int,
        y: Int,
        tile: MapTile?,
        randomize: Boolean,
        original: Operation<Unit>,
    ) {
        original.call(layer, x, y, tile, randomize)
        TerrainTilerWriteCapture.record(layer, x, y)
    }
}

@AsmMixin(TERRAIN_TILER_CLASS, priority = 1000)
object SandboxTerrainTilerScopeMixin {
    @WrapMethod(method = TERRAIN_POINT_METHOD, require = 1)
    fun captureTerrainEdit(
        terrain: TerrainType,
        position: PointF,
        original: Operation<Unit>,
    ) {
        val engine = GameEngine.getInstance()
        val map = engine.map
        if (map == null || (!engine.networkEngine.sandboxMode && !engine.debugEditorMode)) {
            original.call(terrain, position)
            return
        }
        TerrainTilerWriteCapture.withScope(map) {
            original.call(terrain, position)
        }
    }
}

