/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.floor

private const val CELL_SIZE = 56f
private const val GROUP_PADDING = 12f
internal const val AERO_GROUP_SAMPLE_MARGIN = 120f
internal const val AERO_GROUP_REFRESH_NANOS = 83_333_333L
internal const val AERO_BULK_SELECTION_COUNT = 80

internal val aeroUnitGroupColors = listOf(
    Color(0xff5ad6fa), Color(0xffffa461), Color(0xffa8e36f), Color(0xffea83e7), Color(0xfff4dc6d),
    Color(0xff9e9cfc), Color(0xffff7785), Color(0xff5ce7c2), Color(0xff77a5fb), Color(0xfff1b4c8),
)

internal data class AeroUnitGroupVisual(val number: Int, val bounds: Rect, val unitCount: Int)

internal data class AeroSelectionOverlayFrame(
    val groups: List<AeroUnitGroupVisual>, val camera: Offset, val zoom: Float,
    val width: Float, val height: Float, val gameAreaWidth: Float = width,
    val selectionBounds: Rect? = null,
)

/** Cluster occupied cells per native group. Dense stacks cost one unit pass and one visit per cell. */
internal class AeroUnitGroupClusters {
    private class Cell(val x: Int, val y: Int) {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        var count = 0
        var visited = false

        fun include(left: Float, top: Float, right: Float, bottom: Float, count: Int) {
            this.left = minOf(this.left, left)
            this.top = minOf(this.top, top)
            this.right = maxOf(this.right, right)
            this.bottom = maxOf(this.bottom, bottom)
            this.count += count
        }
    }

    private val slots = Array(aeroUnitGroupColors.size) { HashMap<Long, Cell>() }
    private val frontier = ArrayDeque<Cell>()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    fun clear() = slots.forEach { it.clear() }

    fun add(number: Int, x: Float, y: Float, radius: Float) {
        val tileX = floor(x / CELL_SIZE).toInt()
        val tileY = floor(y / CELL_SIZE).toInt()
        val cell = slots[number - 1].getOrPut(key(tileX, tileY)) { Cell(tileX, tileY) }
        cell.include(x - radius, y - radius, x + radius, y + radius, 1)
    }

    fun build(): List<AeroUnitGroupVisual> {
        val visuals = ArrayList<AeroUnitGroupVisual>()
        for ((slot, cells) in slots.withIndex()) {
            for (seed in cells.values) {
                if (seed.visited) continue
                seed.visited = true
                frontier.addLast(seed)
                val area = Cell(seed.x, seed.y)
                while (frontier.isNotEmpty()) {
                    val cell = frontier.removeFirst()
                    area.include(cell.left, cell.top, cell.right, cell.bottom, cell.count)
                    for (y in cell.y - 1..cell.y + 1) for (x in cell.x - 1..cell.x + 1) {
                        val next = cells[key(x, y)] ?: continue
                        if (next.visited) continue
                        next.visited = true
                        frontier.addLast(next)
                    }
                }
                val bounds = Rect(area.left, area.top, area.right, area.bottom).inflate(GROUP_PADDING)
                visuals += AeroUnitGroupVisual(slot + 1, bounds, area.count)
            }
        }
        return visuals.sortedWith(compareByDescending<AeroUnitGroupVisual> { it.unitCount }
            .thenBy { it.number }.thenBy { it.bounds.left }.thenBy { it.bounds.top })
    }
}
