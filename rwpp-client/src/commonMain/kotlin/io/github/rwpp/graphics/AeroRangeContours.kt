/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

internal data class AeroRangeOwnership(val group: Int, val color: Color)

internal fun aeroRangeOwnership(
    playerId: Int,
    allyTeam: Int,
    playerColor: Color,
    useTeamColors: Boolean
): AeroRangeOwnership {
    if (!useTeamColors) return AeroRangeOwnership(playerId, playerColor)
    val colorIndex = if (allyTeam >= 0) allyTeam % 10 else allyTeam
    return AeroRangeOwnership(allyTeam, Color(com.corrodinggames.rts.game.Team.lookupTeamColor(colorIndex)))
}

/** Mask edge shader shared by the JVM Skia and Android RenderEffect implementations. */
internal const val AERO_RANGE_MASK_SHADER = """
    uniform shader content;
    uniform float2 uWorldOrigin;
    uniform float2 uWorldPerPixel;
    uniform float4 uTint;
    uniform float uPhase;
    uniform float uDetailed;

    half4 main(float2 p) {
        float center = content.eval(p).a;
        float inside = smoothstep(0.05, 0.95, center);
        float fillAlpha = inside * 0.025;
        if (uDetailed < 0.5) {
            float left = content.eval(p - float2(1.5, 0.0)).a;
            float right = content.eval(p + float2(1.5, 0.0)).a;
            float up = content.eval(p - float2(0.0, 1.5)).a;
            float down = content.eval(p + float2(0.0, 1.5)).a;
            float neighborMax = max(max(left, right), max(up, down));
            float neighborMin = min(min(left, right), min(up, down));
            float edge = clamp(max(inside * (1.0 - neighborMin),
                                   (1.0 - inside) * neighborMax), 0.0, 1.0);
            float alpha = (fillAlpha + edge * 0.76 * (1.0 - fillAlpha)) * uTint.a;
            return half4(uTint.rgb * alpha, alpha);
        }

        float2 d = float2(1.06, 1.06);
        float upperLeft = content.eval(p + float2(-d.x, -d.y)).a;
        float upperRight = content.eval(p + float2(d.x, -d.y)).a;
        float lowerLeft = content.eval(p + float2(-d.x, d.y)).a;
        float lowerRight = content.eval(p + d).a;
        float left = content.eval(p - float2(1.5, 0.0)).a;
        float right = content.eval(p + float2(1.5, 0.0)).a;
        float up = content.eval(p - float2(0.0, 1.5)).a;
        float down = content.eval(p + float2(0.0, 1.5)).a;
        float neighborMax = max(max(max(left, right), max(up, down)),
            max(max(upperLeft, upperRight), max(lowerLeft, lowerRight)));
        float neighborMin = min(min(min(left, right), min(up, down)),
            min(min(upperLeft, upperRight), min(lowerLeft, lowerRight)));
        float farMax = max(max(content.eval(p - float2(3.5, 0.0)).a,
                               content.eval(p + float2(3.5, 0.0)).a),
                           max(content.eval(p - float2(0.0, 3.5)).a,
                               content.eval(p + float2(0.0, 3.5)).a));
        float innerEdge = inside * (1.0 - neighborMin);
        float outerEdge = (1.0 - inside) * neighborMax;
        float halo = (1.0 - inside) * farMax;
        float edge = clamp(max(innerEdge, outerEdge), 0.0, 1.0);
        float2 world = uWorldOrigin + p * uWorldPerPixel;
        float wave = 0.5 + 0.5 * sin(world.x * 0.043 + world.y * 0.031 - uPhase * 6.2831853);
        float edgeAlpha = edge * mix(0.68, 0.88, wave);
        float haloAlpha = halo * 0.16;
        float alpha = clamp(fillAlpha + edgeAlpha * (1.0 - fillAlpha) +
                            haloAlpha * (1.0 - fillAlpha) * (1.0 - edgeAlpha), 0.0, 1.0) * uTint.a;
        float3 rgb = mix(uTint.rgb, float3(0.78, 0.94, 1.0), edge * 0.34);
        return half4(rgb * alpha, alpha);
    }
"""

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

/** A small shader sampling margin is retained at the viewport edge for its soft outer glow. */
internal fun aeroRangeMaskBounds(
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    viewport: Size,
): Rect? {
    if (group.circles.isEmpty() || viewport.width <= 0f || viewport.height <= 0f) return null
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    group.circles.forEach { circle ->
        val x = (circle.center.x - camera.x) * worldToScreen.x
        val y = (circle.center.y - camera.y) * worldToScreen.y
        val radiusX = circle.radius * worldToScreen.x
        val radiusY = circle.radius * worldToScreen.y
        left = minOf(left, x - radiusX)
        top = minOf(top, y - radiusY)
        right = maxOf(right, x + radiusX)
        bottom = maxOf(bottom, y + radiusY)
    }
    val margin = 5f
    left = maxOf(0f, left - margin)
    top = maxOf(0f, top - margin)
    right = minOf(viewport.width, right + margin)
    bottom = minOf(viewport.height, bottom + margin)
    return if (left < right && top < bottom) Rect(left, top, right, bottom) else null
}

/**
 * Filters invalid circles and groups the remaining samples without constructing Boolean paths.
 * Platform renderers draw each group into one opaque mask, so overlaps do not accumulate alpha
 * and only the outside of the combined mask receives an outline.
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

/** Draw a world-space mask group with a platform shader or a native mask fallback. */
internal expect fun DrawScope.beginAeroRangeFrame(phase: Float)

/** Release platform-owned range-mask resources when the overlay becomes inactive. */
internal expect fun releaseAeroRangeResources()

/** Draw a world-space mask group with a platform shader or a native mask fallback. */
internal expect fun DrawScope.drawAeroRangeGroupMask(
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    worldPerPixel: Offset,
    phase: Float,
    detailed: Boolean,
)

/**
 * Screen-space renderer entry point. Sampling is fresh each frame, so moving units never visibly
 * jump between throttled geometry rebuilds. The path union cost is replaced by a native opaque
 * mask pass followed by one tint/edge composite per team and range kind.
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

    beginAeroRangeFrame(phase)
    if (groups.isEmpty()) {
        releaseAeroRangeResources()
        return
    }

    val worldToScreen = Offset(zoom * size.width / worldWidth, zoom * size.height / worldHeight)
    if (!worldToScreen.x.isFinite() || !worldToScreen.y.isFinite() ||
        worldToScreen.x <= 0f || worldToScreen.y <= 0f
    ) return
    val worldPerPixel = Offset(1f / worldToScreen.x, 1f / worldToScreen.y)
    groups.forEach { group ->
        drawAeroRangeGroupMask(group, camera, worldToScreen, worldPerPixel, phase, detailed)
    }
}

private fun AeroRangeCircle.hasUsableBounds(): Boolean {
    if (!center.x.isFinite() || !center.y.isFinite() || !radius.isFinite() || radius <= 0f) return false
    return (center.x - radius).isFinite() && (center.x + radius).isFinite() &&
            (center.y - radius).isFinite() && (center.y + radius).isFinite()
}
