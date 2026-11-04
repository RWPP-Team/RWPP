/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.building

import android.graphics.PointF
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.building.*
import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.*
import com.corrodinggames.rts.game.units.Unit as GameUnit

private fun terrainEdgeBuildingEnabled(): Boolean = GameVisualSettings.get()?.terrainEdgeBuilding == true

private const val HANDLE_BUILDING_PLACEMENT = "handleBuildingPlacement(FFLandroid/graphics/Point;)V"
private const val FIND_PLACEMENTS =
    "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine.findPlacementPositionsAlongLine(" +
            "Lcom/corrodinggames/rts/game/units/OrderableUnit;FFFFZLjava/util/ArrayList;Lcom/corrodinggames/rts/game/units/Unit;)V"

private fun placeAlongTerrainEdge(
    self: Any,
    building: OrderableUnit,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    drawPreview: Boolean,
    positions: ArrayList<*>?,
    rangeUnit: GameUnit?,
    original: Operation<kotlin.Unit>,
) {
    val aligned = if (terrainEdgeBuildingEnabled()) {
        TerrainEdgeBuildAlignment.samples(GameEngine.getInstance(), building, startX, startY, endX, endY)
    } else null
    if (aligned == null) {
        original.call(self, building, startX, startY, endX, endY, drawPreview, positions, rangeUnit)
    } else {
        val engine = GameEngine.getInstance()
        val map = engine.map
        val footprint = building.constructionFootprint
        if (map == null || footprint == null) {
            original.call(self, building, startX, startY, endX, endY, drawPreview, positions, rangeUnit)
            return
        }

        val candidates = ArrayList<PointF>(MAX_NATIVE_PLACEMENTS)
        val existing = if (positions == null) emptyList() else positions.filterIsInstance<PointF>()
        val nativeCandidateBuffer = ArrayList<PointF>(1)
        val (candidateRangeUnit, previewRangeUnit) = terrainEdgeNativeRangeUnits(rangeUnit)
        val originalBuildingX = building.x
        val originalBuildingY = building.y
        fun callNative(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            draw: Boolean,
            output: ArrayList<*>?,
            unit: GameUnit?,
        ) {
            try {
                original.call(self, building, startX, startY, endX, endY, draw, output, unit)
            } finally {
                // The native method exits before restoring the unit position when it reaches 29.
                building.x = originalBuildingX
                building.y = originalBuildingY
            }
        }

        val originX = building.getMinimapOriginX()
        val originY = building.getMinimapOriginY()
        val resolved = terrainEdgeResolveNativeSamples(
            samples = aligned.points,
            dragAngleDegrees = aligned.dragAngleDegrees,
            gridStep = aligned.gridStep,
            validateNativePlacement = { point ->
                nativeCandidateBuffer.clear()
                // Native findPlacementPositionsAlongLine delegates to checkPlacementPosition,
                // which applies canPlaceAt, Blueprint conflicts, fog, footprint and builder range.
                callNative(
                    point.x - originX, point.y - originY,
                    point.x - originX, point.y - originY,
                    false, nativeCandidateBuffer, candidateRangeUnit,
                )
                nativeCandidateBuffer.firstOrNull()?.let { PointF(it.x, it.y) }
            },
            conflictsWithEarlierDragPlacement = { point, earlierInThisDrag ->
                terrainEdgeCandidateOverlapsEarlierPlacement(
                    point, earlierInThisDrag, existing, building, map.tileWidth, map.tileHeight,
                    footprint.left, footprint.top, footprint.right, footprint.bottom,
                )
            },
            maxPlacements = MAX_NATIVE_PLACEMENTS,
        )
        candidates.addAll(resolved)

        if (candidates.isEmpty()) {
            original.call(self, building, startX, startY, endX, endY, drawPreview, positions, rangeUnit)
            return
        }

        if (drawPreview) {
            // Render precisely the same native-validated points that release will enqueue.
            for (candidate in candidates) {
                val x = candidate.x - building.getMinimapOriginX()
                val y = candidate.y - building.getMinimapOriginY()
                callNative(x, y, x, y, true, null, previewRangeUnit)
            }
        }
        if (!drawPreview && positions != null) {
            @Suppress("UNCHECKED_CAST")
            (positions as ArrayList<PointF>).addAll(candidates)
        }
    }
}

private const val MAX_NATIVE_PLACEMENTS = TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS

/** Higher priority consumes command-candidate ordinal 1 before the preview wrapper is applied. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine", priority = 1100)
object AeroTerrainEdgeBuildingCommandMixin {
    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 1,
        at = At(InjectionPoint.INVOKE, target = FIND_PLACEMENTS),
        require = 1,
    )
    fun candidatesAlongTerrainEdge(
        self: Any,
        building: OrderableUnit,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        drawPreview: Boolean,
        positions: ArrayList<*>?,
        rangeUnit: GameUnit?,
        original: Operation<kotlin.Unit>,
    ) = placeAlongTerrainEdge(
        self, building, startX, startY, endX, endY, drawPreview, positions, rangeUnit, original
    )
}

/** Applied after the command wrapper, so ordinal 0 remains the native preview call. */
@AsmMixin("com/corrodinggames/rts/gameFramework/gui/InterfaceEngine", priority = 1000)
object AeroTerrainEdgeBuildingPreviewMixin {
    @WrapOperation(
        method = HANDLE_BUILDING_PLACEMENT,
        ordinal = 0,
        at = At(InjectionPoint.INVOKE, target = FIND_PLACEMENTS),
        require = 1,
    )
    fun previewAlongTerrainEdge(
        self: Any,
        building: OrderableUnit,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        drawPreview: Boolean,
        positions: ArrayList<*>?,
        rangeUnit: GameUnit?,
        original: Operation<kotlin.Unit>,
    ) = placeAlongTerrainEdge(
        self, building, startX, startY, endX, endY, drawPreview, positions, rangeUnit, original
    )
}
