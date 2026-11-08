/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal data class AeroRangeOwnership(val group: Int, val color: Color)

internal fun aeroRangeOwnership(
    playerId: Int,
    allyTeam: Int,
    playerColor: Color,
    useTeamColors: Boolean
): AeroRangeOwnership {
    if (!useTeamColors) return AeroRangeOwnership(playerId, playerColor)
    // lookupTeamColor indexes a fixed palette. A negative team id must not reach it as a
    // negative index, and ids beyond the palette wrap in the same way as the native HUD.
    val colorIndex = ((allyTeam % 10) + 10) % 10
    return AeroRangeOwnership(allyTeam, Color(com.corrodinggames.rts.game.Team.lookupTeamColor(colorIndex)))
}

/** One circular range in world coordinates. Ranges of different kinds never merge. */
internal data class AeroRangeCircle(
    val center: Offset,
    val radius: Float,
    val team: Int,
    val color: Color,
    val kind: String = "attack",
)

/** The circles that are composited into one opaque mask before tinting. */
internal data class AeroRangeGroup(
    val team: Int,
    val kind: String,
    val color: Color,
    val circles: List<AeroRangeCircle>,
)

/**
 * The engine treats a build range above this value as unlimited
 * (InterfaceEngine.handleBuildingPlacement and OrderableUnit.processBuildWaypoint).
 * Such a range has no visible arc. Expanding it into screen-space geometry instead
 * overflows the path tessellator and paints a solid team-coloured block over the canvas.
 */
internal const val AERO_RANGE_MAX_WORLD_RADIUS = 800_000f

/**
 * A projected radius may never exceed this multiple of the longer viewport edge.
 * A radius beyond it has no visible arc inside the viewport, but its screen-space oval still has
 * to be tessellated. GPU path tessellators have different coordinate and atlas limits, so such
 * geometry is the source of the solid team-coloured blocks reported on some JVM machines.
 * Reject it instead of handing an oversized path to the driver.
 */
internal const val AERO_RANGE_MAX_PROJECTED_RADIUS_FACTOR = 8f

/**
 * Filters invalid circles and groups the remaining samples. Every platform draws the
 * groups as ordinary Compose vector geometry, so overlaps do not accumulate alpha and
 * only the outside of the combined mask receives an outline.
 */
internal class AeroRangeGroupBuilder {
    private val groups = LinkedHashMap<Pair<Int, String>, MutableList<AeroRangeCircle>>()

    fun add(circle: AeroRangeCircle) {
        if (!circle.hasUsableBounds()) return
        groups.getOrPut(circle.team to circle.kind) { ArrayList() }.add(circle)
    }

    fun build(): List<AeroRangeGroup> = groups.map { (key, values) ->
        AeroRangeGroup(key.first, key.second, values.first().color, values.toList())
    }
}

internal fun buildAeroRangeGroups(circles: List<AeroRangeCircle>): List<AeroRangeGroup> {
    val builder = AeroRangeGroupBuilder()
    for (circle in circles) builder.add(circle)
    return builder.build()
}

/** Keep range metadata sampled cheaply while following its native owner every HUD frame. */
internal class AeroRangeTracker<T> {
    private val samples = ArrayList<Pair<T, AeroRangeCircle>>()
    private var cachedSamples: List<Pair<T, AeroRangeCircle>> = emptyList()
    private var dirty = false

    fun clear() {
        if (samples.isNotEmpty()) {
            samples.clear()
            dirty = true
        }
    }

    fun snapshot(): List<Pair<T, AeroRangeCircle>> {
        if (dirty) {
            cachedSamples = samples.toList()
            dirty = false
        }
        return cachedSamples
    }

    fun add(owner: T, circle: AeroRangeCircle) {
        samples += owner to circle
        dirty = true
    }

    fun frame(centerOf: (T) -> Offset?): List<AeroRangeGroup> {
        val builder = AeroRangeGroupBuilder()
        for ((owner, circle) in samples) {
            val center = centerOf(owner) ?: continue
            builder.add(circle.copy(center = center))
        }
        return builder.build()
    }
}

/**
 * Range masks are drawn as ordinary Compose vector geometry on every platform.
 *
 * Both platform renderers originally composited the same mask shader into a native layer: the
 * JVM used Skia saveLayer with an ImageFilter.makeRuntimeShader, Android used saveLayer or a
 * RenderNode with a RuntimeShader RenderEffect. Compose keeps its own save/restore state on the
 * same canvas, so a layer checkpoint could restore Compose's state instead of the layer and leak
 * a team-tinted rectangle. The JVM implementation was already replaced with vector geometry in
 * 2.0.0-beta03, before the desktop screenshots behind this fix were taken. The remaining desktop
 * failure is the geometry itself: a valid placement preview adds a build or attack range whose
 * radius can be unlimited, and expanding that radius into a screen-space path produces a huge
 * oval that the GPU tessellates into a solid team-coloured arc, rectangle or wedge. The radius
 * guards below reject that geometry on both the sampling and drawing paths; the single vector
 * union removes every native layer, render node and shader from both platforms.
 */
internal fun DrawScope.drawAeroRangeGroupMask(
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    worldPerPixel: Offset,
    phase: Float,
    detailed: Boolean,
) {
    if (group.circles.isEmpty()) return
    val viewportWidth = size.width
    val viewportHeight = size.height
    if (viewportWidth <= 0f || viewportHeight <= 0f) return
    if (!worldToScreen.x.isFinite() || !worldToScreen.y.isFinite() ||
        worldToScreen.x <= 0f || worldToScreen.y <= 0f
    ) return
    val maxProjectedRadius =
        max(viewportWidth, viewportHeight) * AERO_RANGE_MAX_PROJECTED_RADIUS_FACTOR
    var union: Path? = null
    for (circle in group.circles) {
        if (circle.radius > AERO_RANGE_MAX_WORLD_RADIUS) continue
        val x = (circle.center.x - camera.x) * worldToScreen.x
        val y = (circle.center.y - camera.y) * worldToScreen.y
        val rx = circle.radius * worldToScreen.x
        val ry = circle.radius * worldToScreen.y
        if (!x.isFinite() || !y.isFinite() || !rx.isFinite() || !ry.isFinite() || rx <= 0f || ry <= 0f) continue
        if (rx > maxProjectedRadius || ry > maxProjectedRadius) continue
        val left = x - rx
        val top = y - ry
        val right = x + rx
        val bottom = y + ry
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) continue
        if (right < 0f || bottom < 0f || left > viewportWidth || top > viewportHeight) continue
        // Never clamp the oval bounds to the viewport: squeezing each edge of the bounding box
        // turns a partially visible circle into an ellipse. Off-screen parts are clipped by the
        // canvas below, which keeps the visible arc circular.
        val oval = Path().apply {
            addOval(Rect(left, top, right, bottom))
        }
        val previous = union
        union = if (previous == null) oval else Path().apply {
            op(previous, oval, PathOperation.Union)
        }
    }
    val merged = union ?: return
    // One filled union keeps overlaps from darkening each other and keeps a single outline,
    // matching the native mask composite that this replaced. The viewport clip is a plain canvas
    // clip, not a layer, so no saveLayer checkpoint can leak a tinted rectangle.
    clipRect(0f, 0f, viewportWidth, viewportHeight) {
        drawPath(merged, group.color.copy(alpha = 0.025f))
        val outlineAlpha = group.color.alpha * if (detailed) 0.8f else 0.76f
        drawPath(
            merged,
            group.color.copy(alpha = outlineAlpha.coerceIn(0f, 1f)),
            style = Stroke(width = if (detailed) 1.4.dp.toPx() else 1.dp.toPx()),
        )
    }
}

/**
 * Screen-space renderer entry point. Sampling is fresh each frame, so moving units never
 * visibly jump between throttled geometry rebuilds. One vector union per team and range
 * kind replaces the native opaque mask pass and its tint/edge composite.
 */
internal fun DrawScope.drawAeroRangeContours(
    groups: List<AeroRangeGroup>,
    camera: Offset,
    zoom: Float,
    worldWidth: Float,
    worldHeight: Float,
    phase: Float,
    detailed: Boolean,
) {
    if (!zoom.isFinite() || zoom <= 0f ||
        !worldWidth.isFinite() || worldWidth <= 0f || !worldHeight.isFinite() || worldHeight <= 0f ||
        size.width <= 0f || size.height <= 0f
    ) return
    if (groups.isEmpty()) return

    val worldToScreen = Offset(zoom * size.width / worldWidth, zoom * size.height / worldHeight)
    if (!worldToScreen.x.isFinite() || !worldToScreen.y.isFinite() ||
        worldToScreen.x <= 0f || worldToScreen.y <= 0f
    ) return
    val worldPerPixel = Offset(1f / worldToScreen.x, 1f / worldToScreen.y)
    groups.forEach { group ->
        drawAeroRangeGroupMask(group, camera, worldToScreen, worldPerPixel, phase, detailed)
    }
}

/** Reject unlimited radii before they reach either the update-phase or render-phase cache. */
internal fun AeroRangeCircle.hasUsableBounds(): Boolean {
    if (!center.x.isFinite() || !center.y.isFinite() || !radius.isFinite() ||
        radius <= 0f || radius > AERO_RANGE_MAX_WORLD_RADIUS
    ) return false
    return (center.x - radius).isFinite() && (center.x + radius).isFinite() &&
            (center.y - radius).isFinite() && (center.y + radius).isFinite()
}
