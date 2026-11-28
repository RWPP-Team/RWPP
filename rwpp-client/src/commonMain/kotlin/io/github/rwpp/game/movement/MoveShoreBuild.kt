/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.game.movement

import kotlin.math.floor

/** A short along-bank approach from the reachable end of a native BUILD path. */
internal object MoveShoreBuild {
    private val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private const val EDGE_INSET = 2f
    private const val CENTER_CLEARANCE = 0.25f
    private const val RANGE_MARGIN = 1f
    private const val MAX_PREDICTIONS = 4

    fun plan(
        grid: MoveNavigationGrid,
        motion: MoveMotion,
        bank: MovePoint,
        build: MoveBuildApproach,
        solidTerrain: (MovePoint) -> Boolean,
    ): List<MovePoint>? {
        val bankX = floor(bank.x / grid.tileWidth) * grid.tileWidth + grid.tileWidth / 2f
        val bankY = floor(bank.y / grid.tileHeight) * grid.tileHeight + grid.tileHeight / 2f
        if (grid.tileWidth <= 2 * EDGE_INSET || grid.tileHeight <= 2 * EDGE_INSET) return null
        val candidates = linkedSetOf<List<MovePoint>>()
        for (offsetX in -1..1) for (offsetY in -1..1) {
            val landX = bankX + offsetX * grid.tileWidth
            val landY = bankY + offsetY * grid.tileHeight
            val land = MovePoint(landX, landY)
            if (!grid.nativeDirectTilesClear(land, land)) continue
            for ((dx, dy) in directions) {
                val edgeX = landX + dx * grid.tileWidth / 2f
                val edgeY = landY + dy * grid.tileHeight / 2f
                if ((build.center.x - edgeX) * dx + (build.center.y - edgeY) * dy <= 0f ||
                    !solidTerrain(MovePoint(landX + dx * grid.tileWidth, landY + dy * grid.tileHeight))) continue
                val shore = if (dx != 0) {
                    val halfSpan = grid.tileHeight / 2f - CENTER_CLEARANCE
                    MovePoint(edgeX - dx * EDGE_INSET, build.center.y.coerceIn(landY - halfSpan, landY + halfSpan))
                } else {
                    val halfSpan = grid.tileWidth / 2f - CENTER_CLEARANCE
                    MovePoint(build.center.x.coerceIn(landX - halfSpan, landX + halfSpan), edgeY - dy * EDGE_INSET)
                }
                if (shore.distanceTo(build.center) > build.range - RANGE_MARGIN) continue
                val inland = (if (dx != 0) grid.tileWidth else grid.tileHeight) * 0.6f - EDGE_INSET
                for (side in -1..1 step 2) {
                    val stage = MovePoint(
                        shore.x - dx * inland + dy * side * grid.tileWidth * 3f,
                        shore.y - dy * inland + dx * side * grid.tileHeight * 3f,
                    )
                    if (straight(grid, motion.position, stage) && straight(grid, stage, shore)) {
                        candidates += listOf(stage, shore)
                    }
                }
            }
        }
        return candidates.sortedBy { (stage, shore) ->
            motion.position.distanceTo(stage) + shore.distanceTo(build.center)
        }.take(MAX_PREDICTIONS).firstOrNull { targets ->
            val position = MoveRoutePlanner.estimateShoreBuildHandoff(grid, motion, targets, CENTER_CLEARANCE)
            position != null && position.distanceTo(build.center) <= build.range - RANGE_MARGIN
        }
    }

    private fun straight(grid: MoveNavigationGrid, from: MovePoint, to: MovePoint): Boolean =
        grid.nativeDirectTilesClear(from, to) && grid.isClear(from, to, CENTER_CLEARANCE)
}
