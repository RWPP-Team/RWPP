/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.ui.requestWorldPresentationFrame
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import com.corrodinggames.rts.game.units.Unit as GameUnit

/**
 * Single-writer structural-sharing cache for immutable frame lists. Its scratch buffer is never
 * published, so render/Compose readers can keep using earlier snapshots while the game thread
 * prepares the next one.
 */
internal class ImmutableFrameListCache<T> {
    private var current: List<T> = emptyList()
    private val scratch = ArrayList<T>()

    fun publishStable(items: List<T>) {
        current = items
        scratch.clear()
    }

    fun update(source: List<T>, transform: (T) -> T?): List<T> {
        scratch.clear()
        var changed = false
        for (item in source) {
            val updated = transform(item)
            if (updated == null) {
                changed = true
            } else {
                if (current.getOrNull(scratch.size) !== updated) changed = true
                scratch.add(updated)
            }
        }
        if (scratch.size != current.size) changed = true
        if (!changed) return current
        current = scratch.toList()
        return current
    }
}

/** Immutable tracking inputs published by HUD sampling, consumed after actual native world draw. */
internal class AeroWorldPresentation {
    private data class Samples(
        val world: NativeWorldFrame,
        val gameTime: Int,
        val frame: WorldVisuals,
        val units: List<Pair<GameUnit, SelectedVisual>>,
        val ranges: List<Pair<GameUnit, AeroRangeCircle>>,
    )

    @Volatile
    private var samples: Samples? = null
    val frame = mutableStateOf<WorldVisuals?>(null)

    private data class CapturedUnitVisual(val sampled: SelectedVisual, val presented: SelectedVisual)
    private data class CapturedRangeState(val x: Float, val y: Float, val visible: Boolean)

    // Native world draw can run faster than HUD sampling. Reuse projected visual objects and
    // immutable lists across those intervening frames when unit state did not change.
    private var unitSource: List<Pair<GameUnit, SelectedVisual>>? = null
    private val unitVisuals = IdentityHashMap<GameUnit, CapturedUnitVisual>()
    private val selectedScratch = ArrayList<SelectedVisual>()
    private var selectedFrame: List<SelectedVisual> = emptyList()

    // Camera motion changes the shader projection, but not range geometry. Avoid rebuilding
    // every circle and group on each native draw when their owners and fog visibility are stable.
    private var rangeSource: List<Pair<GameUnit, AeroRangeCircle>>? = null
    private var rangeViewer: Team? = null
    private val rangeStates = IdentityHashMap<GameUnit, CapturedRangeState>()
    private var rangeFrame: List<AeroRangeGroup> = emptyList()

    @Volatile
    private var resetCapturedCache = false

    fun publish(
        visuals: WorldVisuals, units: List<Pair<GameUnit, SelectedVisual>>,
        ranges: List<Pair<GameUnit, AeroRangeCircle>>
    ) {
        if (visuals.selected.isEmpty() && visuals.orders.isEmpty() && visuals.ranges.isEmpty() &&
            visuals.damageNumbers.isEmpty() && visuals.production.isEmpty()
        ) {
            clear()
        } else publishSamples(visuals, units.toList(), ranges)
    }

    /** The frame path hands over fresh collections that it never mutates after this call. */
    fun publishFrameSnapshot(
        visuals: WorldVisuals,
        units: List<Pair<GameUnit, SelectedVisual>>,
        ranges: List<Pair<GameUnit, AeroRangeCircle>>
    ) {
        if (visuals.selected.isEmpty() && visuals.orders.isEmpty() && visuals.ranges.isEmpty() &&
            visuals.damageNumbers.isEmpty() && visuals.production.isEmpty()
        ) {
            clear()
        } else publishSamples(visuals, units, ranges)
    }

    private fun publishSamples(
        visuals: WorldVisuals, units: List<Pair<GameUnit, SelectedVisual>>,
        ranges: List<Pair<GameUnit, AeroRangeCircle>>
    ) {
        val engine = GameEngine.getInstance()
        if (engine == null) {
            clear(); return
        }
        synchronized(engine.gameUpdateLock) {
            val world = nativeWorldFrame(engine)
            if (world == null) clear()
            else samples = Samples(world, engine.gameTimeMillis, visuals, units, ranges)
        }
    }

    fun clear() {
        samples = null; frame.value = null
        // clear() can be called by Compose disposal while capture() runs on the native draw
        // thread. Let that single writer reset its own scratch state on its next capture.
        resetCapturedCache = true
    }

    private fun resetCapturedStateIfRequested() {
        if (!resetCapturedCache) return
        unitSource = null; unitVisuals.clear(); selectedScratch.clear(); selectedFrame = emptyList()
        rangeSource = null; rangeViewer = null; rangeStates.clear(); rangeFrame = emptyList()
        resetCapturedCache = false
    }

    private fun captureUnits(
        source: List<Pair<GameUnit, SelectedVisual>>,
        world: NativeWorldFrame
    ): List<SelectedVisual> {
        if (unitSource !== source) {
            unitSource = source
            unitVisuals.clear()
            selectedFrame = emptyList()
        }

        selectedScratch.clear()
        var changed = false
        for ((unit, visual) in source) {
            if (unit.team == null || unit.dead || unit.transportedBy != null || !nativeUnitVisible(
                    world,
                    unit
                )
            ) continue
            val center = Offset(unit.x, unit.y - unit.height)
            val selected = visual.selected && unit.selected
            val barRadius = unit.radius
            val barScaledDown = unit.drawnScaledDown
            val previous = unitVisuals[unit]
            val presented = if (previous != null && previous.sampled === visual &&
                previous.presented.center == center && previous.presented.selected == selected &&
                previous.presented.barRadius == barRadius && previous.presented.barScaledDown == barScaledDown
            ) {
                previous.presented
            } else {
                visual.copy(
                    center = center, selected = selected,
                    barRadius = barRadius, barScaledDown = barScaledDown
                ).also { unitVisuals[unit] = CapturedUnitVisual(visual, it) }
            }
            if (selectedFrame.getOrNull(selectedScratch.size) !== presented) changed = true
            selectedScratch += presented
        }
        if (selectedScratch.size != selectedFrame.size) changed = true
        if (!changed) return selectedFrame
        selectedFrame = selectedScratch.toList()
        return selectedFrame
    }

    private fun captureRanges(
        source: List<Pair<GameUnit, AeroRangeCircle>>,
        world: NativeWorldFrame
    ): List<AeroRangeGroup> {
        val viewer = world.viewer
        if (rangeSource === source && rangeViewer === viewer) {
            var unchanged = true
            for ((unit, _) in source) {
                val visible =
                    unit.team != null && !unit.dead && unit.transportedBy == null && nativeUnitVisible(world, unit)
                val previous = rangeStates[unit]
                if (previous == null || previous.visible != visible ||
                    visible && (previous.x != unit.x || previous.y != unit.y)
                ) {
                    unchanged = false
                    break
                }
            }
            if (unchanged) return rangeFrame
        }

        val builder = AeroRangeGroupBuilder()
        rangeStates.clear()
        for ((unit, circle) in source) {
            val visible =
                unit.team != null && !unit.dead && unit.transportedBy == null && nativeUnitVisible(world, unit)
            rangeStates[unit] = CapturedRangeState(unit.x, unit.y, visible)
            if (visible) builder.add(circle.copy(center = Offset(unit.x, unit.y)))
        }
        rangeFrame = builder.build()
        rangeSource = source
        rangeViewer = viewer
        return rangeFrame
    }

    fun capture(engine: GameEngine) {
        // Do not even lock an engine that has already been replaced by the host.
        if (GameEngine.getInstance() !== engine) {
            clear(); return
        }
        synchronized(engine.gameUpdateLock) {
            resetCapturedStateIfRequested()
            val world = nativeWorldFrame(engine)
            val source = samples
            if (world == null || source != null && (!source.world.sameWorld(world) || engine.gameTimeMillis < source.gameTime)) {
                clear()
                resetCapturedStateIfRequested()
                frame.value = WorldVisuals()
                return@synchronized
            }
            if (source == null) return@synchronized
            val selected = captureUnits(source.units, world)
            val ranges = captureRanges(source.ranges, world)
            frame.value = source.frame.copy(
                selected = selected, ranges = ranges,
                camera = Offset(engine.cameraRenderX, engine.cameraRenderY), zoom = engine.zoomScale,
                width = engine.screenWidth.coerceAtLeast(1f), height = engine.screenHeight.coerceAtLeast(1f),
                phase = (System.nanoTime() % 2_000_000_000L) / 2_000_000_000f
            )
            requestWorldPresentationFrame()
        }
    }

}

/** No world scans: only already sampled visible units/ranges are followed in the render phase. */
internal object NativeWorldPresentation {
    private val listeners = CopyOnWriteArrayList<AeroWorldPresentation>()
    fun register(presentation: AeroWorldPresentation): AutoCloseable {
        listeners.add(presentation)
        return AutoCloseable { listeners.remove(presentation); presentation.clear() }
    }

    fun capture(engine: GameEngine) {
        listeners.forEach { it.capture(engine) }
    }
}

