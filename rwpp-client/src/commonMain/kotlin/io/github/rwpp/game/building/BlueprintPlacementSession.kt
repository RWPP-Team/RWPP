/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.building

import kotlin.math.round

/** A captured layout is immutable; input moves only its world origin, never adds buildings. */
internal class BlueprintPlacementSession<T>(
    items: List<BuildingPlanningRelativeItem<T>>,
    originX: Float,
    originY: Float,
) {
    val items: List<BuildingPlanningRelativeItem<T>> = items.toList()
    var originX: Float = originX
        private set
    var originY: Float = originY
        private set

    fun moveTo(x: Float, y: Float) {
        if (x.isFinite() && y.isFinite()) {
            originX = x; originY = y
        }
    }

    fun contains(x: Float, y: Float, padding: Float = 0f): Boolean {
        val placements = placements()
        return !(placements.isEmpty() || !x.isFinite() || !y.isFinite()) && x >= placements.minOf { it.x - it.radius } - padding &&
                x <= placements.maxOf { it.x + it.radius } + padding &&
                y >= placements.minOf { it.y - it.radius } - padding &&
                y <= placements.maxOf { it.y + it.radius } + padding
    }

    fun placements(): List<BuildingPlanningWorldPlacement<T>> =
        buildingPlanningWorldPlacements(items, originX, originY)
}

/** Snap one reference footprint; every other building keeps its captured relative offset. */
internal fun snapBlueprintOrigin(value: Float, offset: Float, footprintOrigin: Float, tileSize: Float): Float {
    if (!value.isFinite() || !tileSize.isFinite() || tileSize <= 0f) return value
    return round((value + offset - footprintOrigin) / tileSize) * tileSize + footprintOrigin - offset
}
