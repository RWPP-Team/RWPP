/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import java.util.*
import kotlin.math.ceil

/** Buildings cast a solid volume silhouette, including courtyards/gaps between sprite layers. */
internal fun solidBuildingShadowPixels(pixels: IntArray, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0 && pixels.size == width * height)
    data class Point(val x: Int, val y: Int)

    val points = ArrayList<Point>()
    for (y in 0 until height) {
        var left = width;
        var right = -1
        for (x in 0 until width) if ((pixels[y * width + x] ushr 24) >= 32) {
            left = minOf(left, x); right = maxOf(right, x)
        }
        if (right >= left) {
            points += Point(left, y); points += Point(right + 1, y)
            points += Point(left, y + 1); points += Point(right + 1, y + 1)
        }
    }
    if (points.isEmpty()) return IntArray(pixels.size)
    val sorted = points.distinct().sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
    fun cross(a: Point, b: Point, c: Point): Long =
        (b.x - a.x).toLong() * (c.y - a.y) - (b.y - a.y).toLong() * (c.x - a.x)

    val hull = ArrayList<Point>()
    for (p in sorted) {
        while (hull.size >= 2 && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0) hull.removeAt(hull.lastIndex)
        hull += p
    }
    val lower = hull.size
    for (p in sorted.asReversed().drop(1)) {
        while (hull.size > lower && cross(hull[hull.lastIndex - 1], hull.last(), p) <= 0) hull.removeAt(hull.lastIndex)
        hull += p
    }
    hull.removeAt(hull.lastIndex)
    val result = IntArray(pixels.size) { (pixels[it] and -0x1000000) or 0x00ffffff }
    for (y in 0 until height) {
        val scan = y + 0.5f
        var left = Float.POSITIVE_INFINITY;
        var right = Float.NEGATIVE_INFINITY
        for (i in hull.indices) {
            val a = hull[i];
            val b = hull[(i + 1) % hull.size]
            if ((a.y <= scan && b.y > scan) || (b.y <= scan && a.y > scan)) {
                val x = a.x + (scan - a.y) * (b.x - a.x) / (b.y - a.y)
                left = minOf(left, x); right = maxOf(right, x)
            }
        }
        if (left.isFinite() && right.isFinite()) {
            for (x in ceil(left - 0.5f).toInt().coerceAtLeast(0) until ceil(right - 0.5f).toInt().coerceAtMost(width))
                result[y * width + x] = -1
        }
    }
    return result
}

/**
 * Holds only units covered during one native drawGame call. Identity semantics matter here: units
 * are native mutable game objects and must not be looked up through equals/hashCode implementations.
 */
internal class ProjectedShadowCoverage<T : Any> {
    private val covered = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var active = false

    fun beginDrawGame() {
        covered.clear()
        active = true
    }

    fun mark(unit: T) {
        if (active) covered += unit
    }

    fun shouldReplace(unit: T, enabled: Boolean): Boolean = active && enabled && covered.contains(unit)

    fun endDrawGame() {
        active = false
        covered.clear()
    }
}

/** Disable JavaCanvas's atlas only for the projected-shadow pass, and restore it on every exit. */
internal inline fun <A : Any, T> withTextureAtlasTemporarilyDisabled(
    readAtlas: () -> A?,
    writeAtlas: (A?) -> Unit,
    draw: () -> T
): T {
    val atlas = readAtlas() ?: return draw()
    writeAtlas(null)
    return try {
        draw()
    } finally {
        writeAtlas(atlas)
    }
}

/** Keep PaintGLShader's native vertex loader while pinning our fragment across file-watch reloads. */
internal inline fun <T> pinProjectedShadowFragmentAfterNativeReload(
    readNativeSources: () -> T,
    writeFragmentSource: (String) -> Unit
): T {
    val result = readNativeSources()
    writeFragmentSource(WorldShadowShaderSources.FRAGMENT_SHADER)
    return result
}

