/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

internal const val TERRAIN_SIGNATURE_AUDIT_INTERVAL_FRAMES = 16

internal fun terrainSignatureAuditDue(frame: Long, interval: Int = TERRAIN_SIGNATURE_AUDIT_INTERVAL_FRAMES): Boolean {
    require(interval > 0)
    return frame > 0L && frame % interval == 0L
}

internal data class TerrainMaterialPerformanceStats(
    val mapFrames: Long,
    val tileSignatureAudits: Long,
    val signatureTilesScanned: Long,
    val terrainMaskBuilds: Long,
    val mutationTriggeredBuilds: Long,
    val auditTriggeredBuilds: Long
)

/** Signed distance in tile units, measured to the boundary rather than the opposite tile centre. */
internal fun terrainCoastDistances(
    water: BooleanArray, width: Int, height: Int, limit: Float = 6f,
    scratch: FloatArray? = null, output: FloatArray? = null
): FloatArray {
    require(water.size == width * height && limit > 0f)
    // Chamfer distance approximates Euclidean distance in two linear passes. The old 4-neighbour
    // flood fill produced a Manhattan diamond around every shore bend, which showed as stepped
    // bands when the distance field drove the wet-ground and wave fade.
    val cap = ceil(limit).toInt().toFloat() + 1f
    val distance = scratch?.takeIf { it.size == water.size } ?: FloatArray(water.size)
    distance.fill(cap)
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        if ((x > 0 && water[i] != water[i - 1]) || (x + 1 < width && water[i] != water[i + 1]) ||
            (y > 0 && water[i] != water[i - width]) || (y + 1 < height && water[i] != water[i + width])
        ) {
            distance[i] = 0f
        }
    }
    val diagonal = 1.41421356f
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        var best = distance[i]
        if (x > 0 && water[i] == water[i - 1]) best = min(best, distance[i - 1] + 1f)
        if (y > 0 && water[i] == water[i - width]) best = min(best, distance[i - width] + 1f)
        if (x > 0 && y > 0 && water[i] == water[i - width - 1]) best = min(best, distance[i - width - 1] + diagonal)
        if (x + 1 < width && y > 0 && water[i] == water[i - width + 1]) best =
            min(best, distance[i - width + 1] + diagonal)
        distance[i] = min(best, cap)
    }
    for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
        val i = y * width + x
        var best = distance[i]
        if (x + 1 < width && water[i] == water[i + 1]) best = min(best, distance[i + 1] + 1f)
        if (y + 1 < height && water[i] == water[i + width]) best = min(best, distance[i + width] + 1f)
        if (x + 1 < width && y + 1 < height && water[i] == water[i + width + 1]) best =
            min(best, distance[i + width + 1] + diagonal)
        if (x > 0 && y + 1 < height && water[i] == water[i + width - 1]) best =
            min(best, distance[i + width - 1] + diagonal)
        distance[i] = min(best, cap)
    }
    val result = output?.takeIf { it.size == water.size } ?: FloatArray(water.size)
    for (i in result.indices) result[i] = min(distance[i] + 0.5f, limit) * if (water[i]) 1f else -1f
    return result
}

/** Keep material data nontransparent through native image operations; alpha encodes lava independently. */
internal fun terrainMaterialMaskPixel(water: Boolean, coastDistance: Float, lava: Boolean): Int =
    terrainMaterialMaskPixel(if (water) 1f else 0f, coastDistance, if (lava) 1f else 0f, false)

internal fun terrainMaterialMaskPixel(
    waterCoverage: Float,
    coastDistance: Float,
    lavaCoverage: Float,
    cliff: Boolean = false,
): Int {
    val distance = ((coastDistance.coerceIn(-6f, 6f) / 12f + 0.5f) * 255f).roundToInt()
    val water = (waterCoverage.coerceIn(0f, 1f) * 255).roundToInt()
    val lava = 128 + (lavaCoverage.coerceIn(0f, 1f) * 127).roundToInt()
    // Blue contains terrain shape only. Fog must not suppress terrain, water, or lighting.
    val cliffMarker = if (cliff) 38 else 255
    return (lava shl 24) or (water shl 16) or (distance shl 8) or cliffMarker
}

/** GPU-only createImage leaves JavaBitmap's CPU pixel stride unset; use the ImageBuffer-backed path. */
internal fun createTerrainMaskImage(graphics: GraphicsEngine, width: Int, height: Int): BitmapOrTexture =
    graphics.createImageImpl(width, height, true)

