/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.movement

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A bounded copy of terrain and known buildings. Queries never touch native mutable cost grids. */
internal class MoveNavigationGrid(
    val originX: Float,
    val originY: Float,
    val width: Int,
    val height: Int,
    val tileWidth: Float,
    val tileHeight: Float,
    blocked: BooleanArray,
) {
    private val cells = blocked.copyOf()

    init {
        require(originX.isFinite() && originY.isFinite())
        require(width in 1..512 && height in 1..512 && width.toLong() * height <= MAX_CELLS)
        require(tileWidth.isFinite() && tileWidth > 0f && tileHeight.isFinite() && tileHeight > 0f)
        require(cells.size == width * height)
    }

    /** Replayable debug mask: column-major cells, four low-bit-first cells per hex digit. */
    fun blockedMaskHex(): String = buildString((cells.size + 3) / 4) {
        for (base in cells.indices step 4) {
            var bits = 0
            for (offset in 0..3) if (base + offset < cells.size && cells[base + offset]) {
                bits = bits or (1 shl offset)
            }
            append("0123456789abcdef"[bits])
        }
    }

    fun contains(point: MovePoint, radius: Float = 0f): Boolean = point.finite &&
            radius.isFinite() && radius >= 0f && point.x - radius >= originX &&
            point.y - radius >= originY && point.x + radius < originX + width * tileWidth &&
            point.y + radius < originY + height * tileHeight

    /**
     * The game's direct MOVE test walks tile indices, which can hit a different tile
     * than our world-space segment. Check its blocked-cell traversal on the snapshot.
     * The game also weighs live costs, which this client snapshot does not contain.
     */
    fun nativeDirectTilesClear(from: MovePoint, to: MovePoint): Boolean {
        if (!contains(from) || !contains(to)) return false
        var x = ((from.x - originX) / tileWidth).toInt()
        var y = ((from.y - originY) / tileHeight).toInt()
        val endX = ((to.x - originX) / tileWidth).toInt()
        val endY = ((to.y - originY) / tileHeight).toInt()
        val dx = abs(endX - x)
        val dy = abs(endY - y)
        val stepX = if (endX > x) 1 else -1
        val stepY = if (endY > y) 1 else -1
        var error = dx - dy
        repeat(dx + dy + 1) {
            if (x !in 0 until width || y !in 0 until height) return false
            if (cells[x * height + y]) return false
            if (x == endX && y == endY) return true
            when {
                error > 0 -> { x += stepX; error -= 2 * dy }
                error < 0 -> { y += stepY; error += 2 * dx }
                else -> { x += stepX; y += stepY; error += 2 * (dx - dy) }
            }
        }
        return false
    }

    /** Swept disc, including corners. Sampling only identifies cells; collision tests are exact. */
    fun isClear(from: MovePoint, to: MovePoint, radius: Float): Boolean {
        if (!contains(from, radius) || !contains(to, radius)) return false
        val reachX = ceil(radius / tileWidth).toInt() + 1
        val reachY = ceil(radius / tileHeight).toInt() + 1
        val steps = max(1, ceil(max(abs(to.x - from.x) / tileWidth, abs(to.y - from.y) / tileHeight) * 2f).toInt())
        for (step in 0..steps) {
            val fraction = step.toFloat() / steps
            val cx = floor((from.x + (to.x - from.x) * fraction - originX) / tileWidth).toInt()
            val cy = floor((from.y + (to.y - from.y) * fraction - originY) / tileHeight).toInt()
            for (x in max(0, cx - reachX)..min(width - 1, cx + reachX)) {
                for (y in max(0, cy - reachY)..min(height - 1, cy + reachY)) {
                    if (!cells[x * height + y]) continue
                    val left = originX + x * tileWidth
                    val top = originY + y * tileHeight
                    if (discHitsRect(from, to, radius, left, top, left + tileWidth, top + tileHeight)) return false
                }
            }
        }
        return true
    }

    /** Two offsets let a time-based search choose between a close corner and a wider turn. */
    fun corners(start: MovePoint, goal: MovePoint, radius: Float, limit: Int = 96): List<MovePoint> {
        require(limit in 1..256)
        data class Corner(val point: MovePoint, val cost: Float)
        val order = compareBy<Corner> { it.cost }.thenBy { it.point.x }.thenBy { it.point.y }
        // Scan the entire bounded snapshot. A dense prefix must not erase every candidate or hide
        // later exits. Keep a bounded heap instead of an unbounded quadratic duplicate scan.
        val poolLimit = max(256, limit * 2)
        val pool = PriorityQueue(order.reversed())
        val margin = radius + min(tileWidth, tileHeight) * 0.12f + 1f
        for (x in 1 until width) for (y in 1 until height) {
            val nw = cells[(x - 1) * height + y - 1]
            val ne = cells[x * height + y - 1]
            val sw = cells[(x - 1) * height + y]
            val se = cells[x * height + y]
            if ((if (nw) 1 else 0) + (if (ne) 1 else 0) + (if (sw) 1 else 0) + (if (se) 1 else 0) != 1) continue
            val sx = if (nw || sw) 1f else -1f
            val sy = if (nw || ne) 1f else -1f
            for (scale in floatArrayOf(1f, 1.8f)) {
                val p = MovePoint(originX + x * tileWidth + sx * margin * scale, originY + y * tileHeight + sy * margin * scale)
                if (!isClear(p, p, radius)) continue
                val candidate = Corner(p, start.distanceTo(p) + p.distanceTo(goal))
                if (pool.size < poolLimit) pool.add(candidate)
                else if (order.compare(candidate, pool.peek()) < 0) {
                    pool.remove()
                    pool.add(candidate)
                }
            }
        }
        val result = ArrayList<MovePoint>(limit)
        for (candidate in pool.toList().sortedWith(order)) {
            if (result.none { it.distanceTo(candidate.point) < 2f }) result.add(candidate.point)
            if (result.size == limit) break
        }
        return result
    }

    companion object {
        const val MAX_CELLS = 16_384

        private fun discHitsRect(a: MovePoint, b: MovePoint, radius: Float, l: Float, t: Float, r: Float, d: Float): Boolean {
            var low = 0f
            var high = 1f
            fun clip(p: Float, delta: Float, minValue: Float, maxValue: Float): Boolean {
                if (abs(delta) < 0.000001f) return p in minValue..maxValue
                val first = (minValue - p) / delta
                val last = (maxValue - p) / delta
                low = max(low, min(first, last))
                high = min(high, max(first, last))
                return low <= high
            }
            if (clip(a.x, b.x - a.x, l, r) && clip(a.y, b.y - a.y, t, d)) return true
            val radiusSquared = radius * radius + 0.0001f
            fun endpointDistanceSquared(p: MovePoint): Float {
                val dx = max(max(l - p.x, 0f), p.x - r)
                val dy = max(max(t - p.y, 0f), p.y - d)
                return dx * dx + dy * dy
            }
            if (endpointDistanceSquared(a) <= radiusSquared || endpointDistanceSquared(b) <= radiusSquared) return true
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared <= 0.000001f) return false
            fun cornerDistanceSquared(x: Float, y: Float): Float {
                val projection = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
                val ex = x - a.x - projection * dx
                val ey = y - a.y - projection * dy
                return ex * ex + ey * ey
            }
            return cornerDistanceSquared(l, t) <= radiusSquared || cornerDistanceSquared(r, t) <= radiusSquared ||
                    cornerDistanceSquared(l, d) <= radiusSquared || cornerDistanceSquared(r, d) <= radiusSquared
        }
    }
}
