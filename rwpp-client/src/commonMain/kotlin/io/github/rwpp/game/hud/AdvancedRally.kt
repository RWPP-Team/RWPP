/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import android.graphics.PointF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.logic.GameVisualSettings
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import com.corrodinggames.rts.game.units.Unit as GameUnit

private const val NATIVE_WAYPOINT_CAPACITY = 29
internal const val MAX_RALLY_POINTS = NATIVE_WAYPOINT_CAPACITY - 1 // Reserve the factory exit waypoint.

/** Native state and routes are accessed on the game thread. The HUD reads the published draft. */
internal object AdvancedRally {
    class Editor(
        val action: SpecialAction,
        val selectionRevision: Int,
        val factories: List<OrderableUnit>,
    )

    data class Draft(val editor: Editor, val points: List<Offset>)
    private data class Route(val owner: Team, val points: List<Offset>)
    private data class FactoryRoute(val desired: Route, val previous: Route?)

    enum class Edit { UNDO, RESET, SAVE, CANCEL }

    private val routes = WeakHashMap<FactoryBuildQueue, FactoryRoute>()
    private var worldGeneration = 0L
    var draft by mutableStateOf<Draft?>(null)
        private set

    private fun enabled() = GameVisualSettings.get()?.advancedRallyPoints == true

    fun refresh(engine: GameEngine) {
        val ui = engine.interfaceEngine
        val action = ui.activeSpecialAction
        val owner = engine.playerTeam
        if (action?.actionType != ActionType.setRally || owner == null || !enabled() ||
            engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)
        ) {
            draft = null
            return
        }
        val revision = ui.selectionChangeCounter
        val editor = draft?.editor
        if (editor?.action === action && editor.selectionRevision == revision) return

        val factories = ui.getUnitsForSpecialAction(action).filterIsInstance<OrderableUnit>().filter { unit ->
            !unit.dead && unit.team === owner && ui.canPlayerControlUnit(unit) &&
                NativeFactoryOverlays.queue(unit) != null &&
                unit.specialActions?.any { it is SpecialAction && it.actionType == ActionType.setRally } == true
        }
        draft = factories.takeIf { it.isNotEmpty() }?.let {
            val stored = factories.map { factory ->
                NativeFactoryOverlays.queue(factory)?.let { appliedRoute(it, owner)?.points }
                    ?: listOfNotNull(NativeFactoryOverlays.rallyPoint(factory))
            }
            val first = stored.first()
            Draft(Editor(action, revision, factories), first.takeIf { points -> stored.all { it == points } }.orEmpty())
        }
    }

    /** The native target-ground handler already filters UI clicks, drag gestures and invalid taps. */
    fun recordPoint(engine: GameEngine, x: Float, y: Float): Boolean {
        refresh(engine)
        val current = draft ?: return false
        val point = Offset(x, y)
        if (x.isFinite() && y.isFinite() && current.points.size < MAX_RALLY_POINTS &&
            current.points.lastOrNull() != point
        ) {
            draft = current.copy(points = current.points + point)
        }
        return true
    }

    fun edit(engine: GameEngine, expected: Editor, edit: Edit) {
        refresh(engine)
        val current = draft?.takeIf { it.editor === expected } ?: return
        val ui = engine.interfaceEngine
        when (edit) {
            Edit.UNDO -> draft = current.copy(points = current.points.dropLast(1))
            Edit.RESET -> draft = current.copy(points = emptyList())
            Edit.CANCEL -> ui.cancelActiveAction()
            Edit.SAVE -> {
                val owner = engine.playerTeam ?: return
                val factories = current.editor.factories.filter { !it.dead && it.team === owner && ui.canPlayerControlUnit(it) }
                if (factories.isNotEmpty()) {
                    val route = Route(owner, current.points)
                    for (factory in factories) {
                        val queue = NativeFactoryOverlays.queue(factory) ?: continue
                        routes[queue] = FactoryRoute(route, appliedRoute(queue, owner))
                    }
                    // Send the first point through the native rally command. It takes effect at
                    // the usual lockstep boundary and remains useful in saves and ordinary clients.
                    current.points.firstOrNull()?.let { first ->
                        ui.getPlayerCommand().apply {
                            factories.forEach(::addUnit)
                            setFactoryRallyPoint(PointF(first.x, first.y))
                        }
                    }
                }
                ui.cancelActiveAction()
            }
        }
    }

    fun nativeActionCancelled() {
        draft = null
    }

    /** Quick rally and ordinary rally are single-point commands. */
    fun replaceWithNativeRally(ui: InterfaceEngine) {
        if (routes.isEmpty()) return
        for (entry in ui.selectedUnits) {
            val unit = entry as? OrderableUnit ?: continue
            if (ui.canPlayerControlUnit(unit)) NativeFactoryOverlays.queue(unit)?.let(routes::remove)
        }
    }

    fun unitProduced(engine: GameEngine, queue: FactoryBuildQueue, produced: GameUnit, exitOffset: Float) {
        val unit = produced as? OrderableUnit ?: return
        val owner = engine.playerTeam ?: return
        if (routes.isEmpty() || !enabled() || unit.team !== owner || unit.moveSpeed <= 0f ||
            engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)
        ) return
        val route = appliedRoute(queue, owner) ?: return
        val points = route.points
        if (points.size == 1 || (points.isEmpty() && (queue.rallyPoint == null || unit.waypointCount == 0))) return
        val nativeRally = if (points.isEmpty()) unit.getWaypoint(unit.waypointCount - 1) ?: return else null
        val exit = if (nativeRally != null && exitOffset != 0f) unit.getWaypoint(0) else null
        val map = engine.map ?: return
        val generation = worldGeneration
        // Factory completion can run inside command dispatch. Enqueue on the next native frame
        // to avoid mutating the list currently being dispatched, and let the unit register first.
        GameScope.launch {
            if (generation != worldGeneration || engine.map !== map || !map.mapLoaded || unit.dead || unit.removed ||
                unit.team !== owner || engine.playerTeam !== owner || !enabled() ||
                engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)
            ) return@launch
            if (nativeRally != null) {
                // Native rally commands cannot send a null point. Clear the inherited rally
                // with a normal unit command, preserving the factory exit if it is still active.
                if (unit.waypointCount == 0 || unit.getWaypoint(unit.waypointCount - 1) !== nativeRally) return@launch
                engine.interfaceEngine.getPlayerCommand().apply {
                    addUnit(unit)
                    setClearWaypointsFirst()
                    if (exit != null && unit.getWaypoint(0) === exit) setMoveWaypoint(exit.targetX, exit.targetY)
                }
                return@launch
            }
            val count = minOf(points.size - 1, NATIVE_WAYPOINT_CAPACITY - unit.waypointCount)
            for (index in 1..count) {
                val point = points[index]
                engine.interfaceEngine.getPlayerCommand().apply {
                    addUnit(unit)
                    queueOrder = true
                    setMoveWaypoint(point.x, point.y)
                    // Queued waypoints find paths when they become active. Independent path
                    // preparation before sending can reorder commands if the unit has stopped.
                    pathfindingQueued = true
                }
            }
        }
    }

    fun displayedRoute(unit: OrderableUnit): List<Offset>? {
        if (!unit.selected || !enabled()) return null
        val owner = unit.team ?: return null
        val current = draft
        if (current != null && unit in current.editor.factories) return current.points
        val queue = NativeFactoryOverlays.queue(unit) ?: return null
        return appliedRoute(queue, owner)?.points?.takeUnless { it.size == 1 }
    }

    fun resetWorld() {
        worldGeneration++
        routes.clear()
        draft = null
    }

    private fun appliedRoute(queue: FactoryBuildQueue, owner: Team): Route? {
        val route = routes[queue] ?: return null
        val point = queue.rallyPoint?.let { Offset(it.x, it.y) }
        fun matches(candidate: Route) = candidate.owner === owner &&
                (candidate.points.isEmpty() || candidate.points.first() == point)
        if (matches(route.desired)) {
            if (route.previous != null) routes[queue] = FactoryRoute(route.desired, null)
            return route.desired
        }
        return route.previous?.takeIf(::matches)
    }
}
