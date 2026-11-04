/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.building

import android.graphics.PointF
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.GameEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal data class TerrainEdgeNativeSamples(
    val points: Iterable<PointF>,
    val dragAngleDegrees: Float,
    val gridStep: Float,
)

/** Candidate discovery ignores builder range; native preview still draws range reachability. */
internal fun <T> terrainEdgeNativeRangeUnits(rangeUnit: T?): Pair<T?, T?> = null to rangeUnit

/**
 * Reproduces InterfaceEngine.findPlacementPositionsAlongLine's sample cadence. The caller's
 * snap function must use Map.snapToTile and return the snapped map-local pixel position.
 */
internal fun terrainEdgeNativeDragSamples(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    originX: Float,
    originY: Float,
    nativeSampleStep: Float,
    gridStep: Float,
    snapToNativeGrid: (Float, Float) -> PointF,
): TerrainEdgeNativeSamples? {
    if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite() ||
        !originX.isFinite() || !originY.isFinite() ||
        nativeSampleStep <= 0f || !nativeSampleStep.isFinite() || gridStep <= 0f || !gridStep.isFinite()
    ) return null

    val snappedStart = snapToNativeGrid(startX, startY)
    val worldStartX = snappedStart.x + originX
    val worldStartY = snappedStart.y + originY
    val worldEndX = endX + originX
    val worldEndY = endY + originY
    val distance = CommonUtils.distance(worldStartX, worldStartY, worldEndX, worldEndY)
    if (!distance.isFinite()) return null

    val angle = CommonUtils.angleBetweenPoints(worldStartX, worldStartY, worldEndX, worldEndY)
    val points = sequence {
        var distanceAlong = 0f
        while (distanceAlong <= distance) {
            val sampleWorldX = worldStartX + CommonUtils.cos(angle) * distanceAlong
            val sampleWorldY = worldStartY + CommonUtils.sin(angle) * distanceAlong
            val snapped = snapToNativeGrid(sampleWorldX - originX, sampleWorldY - originY)
            // Keep duplicates: the resolver skips a repeated raw tile the same way the native
            // placeholder-unit collision check skips a sample already accepted on this drag.
            yield(PointF(snapped.x + originX, snapped.y + originY))
            val nextDistance = distanceAlong + nativeSampleStep
            if (nextDistance <= distanceAlong) return@sequence
            distanceAlong = nextDistance
        }
    }.asIterable()
    return TerrainEdgeNativeSamples(points, angle, gridStep)
}

internal fun terrainEdgeSnapAxisAngle(angleDegrees: Float): Float {
    val nearestAxis = kotlin.math.round(angleDegrees / 90f) * 90f
    var difference = (angleDegrees - nearestAxis) % 360f
    if (difference > 180f) difference -= 360f
    if (difference < -180f) difference += 360f
    return if (kotlin.math.abs(difference) < TERRAIN_EDGE_AXIS_SNAP_DEGREES) nearestAxis else angleDegrees
}

internal fun terrainEdgeSearchNormal(angleDegrees: Float): PointF {
    val snappedAngle = terrainEdgeSnapAxisAngle(angleDegrees)
    val angle = Math.toRadians(snappedAngle.toDouble())
    val directionX = cos(angle)
    val directionY = sin(angle)
    // The reference keeps the perpendicular axis-aligned: horizontal-ish drags search down/up;
    // vertical-ish drags search right/left. Ties follow its vertical branch.
    return if (abs(directionX) > abs(directionY)) PointF(0f, 1f) else PointF(1f, 0f)
}

/**
 * Processes native drag samples in order. [validateNativePlacement] must call the game's native
 * placement checker so canPlaceAt, Blueprint conflicts, footprint and fog rules stay authoritative.
 * A null result means that sample cannot be placed. The side lock belongs to this invocation only.
 */
internal fun terrainEdgeResolveNativeSamples(
    samples: Iterable<PointF>,
    dragAngleDegrees: Float,
    gridStep: Float,
    validateNativePlacement: (PointF) -> PointF?,
    conflictsWithEarlierDragPlacement: (PointF, List<PointF>) -> Boolean = { _, _ -> false },
    maxPlacements: Int = TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS,
): List<PointF> {
    if (!dragAngleDegrees.isFinite() || gridStep <= 0f || !gridStep.isFinite() || maxPlacements <= 0
    ) return emptyList()

    val placementLimit = minOf(maxPlacements, TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS)
    val normal = terrainEdgeSearchNormal(dragAngleDegrees)
    val normalX = normal.x
    val normalY = normal.y
    val halfGridStep = gridStep / 2f
    val maxSearchOffset = gridStep * TERRAIN_EDGE_MAX_SEARCH_GRIDS
    val placements = ArrayList<PointF>(placementLimit)
    var lockedSide = 0
    var previousRawSample: PointF? = null
    val sampleIterator = samples.iterator()

    while (placements.size < placementLimit && sampleIterator.hasNext()) {
        val sample = sampleIterator.next()
        val previous = previousRawSample
        previousRawSample = sample
        if (previous != null && previous.x == sample.x && previous.y == sample.y) continue

        val direct = validateNativePlacement(sample)
        if (direct != null) {
            // Any directly legal raw sample starts a fresh run, even when native duplicate
            // placement checks require skipping it from this batch.
            lockedSide = 0
            if (!conflictsWithEarlierDragPlacement(direct, placements)) {
                placements += PointF(direct.x, direct.y)
            }
            // The native line placer skips a raw sample already covered by a prior accepted
            // placeholder; it does not move that duplicate onto a second row.
            continue
        }

        val sides = if (lockedSide == 0) intArrayOf(1, -1) else intArrayOf(lockedSide)
        var found: PointF? = null
        var foundSide = 0
        var offset = halfGridStep
        while (offset <= maxSearchOffset && found == null) {
            for (side in sides) {
                val shifted = PointF(
                    sample.x + normalX * offset * side,
                    sample.y + normalY * offset * side,
                )
                val candidate = validateNativePlacement(shifted)
                if (candidate != null && !conflictsWithEarlierDragPlacement(candidate, placements)) {
                    found = candidate
                    foundSide = side
                    break
                }
            }
            offset += halfGridStep
        }
        if (found != null) {
            placements += PointF(found.x, found.y)
            if (lockedSide == 0) lockedSide = foundSide
        }
    }
    return placements
}

internal const val TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS = 29
private const val TERRAIN_EDGE_AXIS_SNAP_DEGREES = 5f
private const val TERRAIN_EDGE_MAX_SEARCH_GRIDS = 8

internal fun terrainEdgeCandidateOverlapsEarlierPlacement(
    candidate: PointF,
    current: List<PointF>,
    existing: List<PointF>,
    building: OrderableUnit,
    tileWidth: Int,
    tileHeight: Int,
    footprintLeft: Int,
    footprintTop: Int,
    footprintRight: Int,
    footprintBottom: Int,
): Boolean {
    if (tileWidth <= 0 || tileHeight <= 0) return true
    val originX = building.getMinimapOriginX()
    val originY = building.getMinimapOriginY()
    val candidateTileX = floor((candidate.x - originX) / tileWidth).toInt()
    val candidateTileY = floor((candidate.y - originY) / tileHeight).toInt()
    val combinedRadius = if (building.isBuilding) 9f else (building.radius * 2f).coerceAtLeast(11f)

    fun overlaps(prior: PointF): Boolean {
        val priorTileX = floor((prior.x - originX) / tileWidth).toInt()
        val priorTileY = floor((prior.y - originY) / tileHeight).toInt()
        val overlapsFootprint = candidateTileX + footprintLeft <= priorTileX + footprintRight &&
                priorTileX + footprintLeft <= candidateTileX + footprintRight &&
                candidateTileY + footprintTop <= priorTileY + footprintBottom &&
                priorTileY + footprintTop <= candidateTileY + footprintBottom
        val dx = candidate.x - prior.x
        val dy = candidate.y - prior.y
        return overlapsFootprint || dx * dx + dy * dy < combinedRadius * combinedRadius
    }

    return current.any(::overlaps) || existing.any(::overlaps)
}

/** Native placement is authoritative; PathEngine costs are intentionally not consulted here. */
internal object TerrainEdgeBuildAlignment {
    fun samples(
        engine: GameEngine,
        building: OrderableUnit,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): TerrainEdgeNativeSamples? {
        val map = engine.map ?: return null
        if (map.halfTileWidth <= 0 || map.tileWidth <= 0 || map.tileHeight <= 0) return null
        val originX = building.getMinimapOriginX()
        val originY = building.getMinimapOriginY()
        return terrainEdgeNativeDragSamples(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            originX = originX,
            originY = originY,
            nativeSampleStep = map.halfTileWidth.toFloat(),
            gridStep = minOf(map.tileWidth, map.tileHeight).toFloat(),
            snapToNativeGrid = { x, y ->
                map.snapToTile(x, y)
                PointF(map.fogRevealTileX.toFloat(), map.fogRevealTileY.toFloat())
            },
        )
    }
}
