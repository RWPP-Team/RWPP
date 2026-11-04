/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.building

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.effects.Blueprint
import com.corrodinggames.rts.gameFramework.utility.DeferredArrayList
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hotkeys.MouseControlPreferencesController
import io.github.rwpp.mixin.building.NativeBuildingPlanningCommandAccess
import io.github.rwpp.mixin.hud.NativeInterfaceAccess
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.corrodinggames.rts.game.units.Unit as GameUnit

// allocateWaypoint() exposes 29 queued entries; slot 29 is native scratch space.
private const val MAX_PLANNING_TARGETS = 29
private const val PLANNING_DRAG_THRESHOLD_DIP = 7f

internal enum class BuildingPlanningMode {
    OFF,
    READY,
    SELECT_BLUEPRINT,
    PLACE_BLUEPRINT,
    RECLAIM_AREA,
}

/** Immutable state that native HUD or Android controls can read without touching game objects. */
internal data class BuildingPlanningSnapshot(
    val enabled: Boolean = false,
    val mode: BuildingPlanningMode = BuildingPlanningMode.OFF,
    val selectedBuilderIds: List<Long> = emptyList(),
    val blueprint: List<BuildingPlanningBlueprintItem> = emptyList(),
    val statusKey: String? = null,
    val lastActionCount: Int = 0,
    val dragRect: BuildingPlanningScreenRect? = null,
)

internal data class BuildingPlanningBlueprintItem(
    val typeInternalName: String,
    val techLevel: Int,
    val offsetX: Float,
    val offsetY: Float,
)

internal data class BuildingPlanningScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class BuildingPlanningRelativeItem<T>(
    val value: T,
    val techLevel: Int,
    val offsetX: Float,
    val offsetY: Float,
    val radius: Float = 12f,
)

internal data class BuildingPlanningWorldPlacement<T>(
    val value: T,
    val techLevel: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
)

/** A fixed cap keeps a drag or copied layout from creating an unbounded native command burst. */
internal fun <T> buildingPlanningWorldPlacements(
    items: List<BuildingPlanningRelativeItem<T>>,
    originX: Float,
    originY: Float,
    limit: Int = MAX_PLANNING_TARGETS,
): List<BuildingPlanningWorldPlacement<T>> {
    if (!originX.isFinite() || !originY.isFinite() || limit <= 0) return emptyList()
    return items.asSequence().take(limit).filter {
        it.offsetX.isFinite() && it.offsetY.isFinite() && it.radius.isFinite()
    }.map {
        BuildingPlanningWorldPlacement(
            value = it.value,
            techLevel = it.techLevel,
            x = originX + it.offsetX,
            y = originY + it.offsetY,
            radius = it.radius.coerceAtLeast(1f),
        )
    }.toList()
}

internal fun buildingPlanningNormalizedRect(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): BuildingPlanningScreenRect? {
    if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
    return BuildingPlanningScreenRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
}

internal fun buildingPlanningRectTouchesPoint(
    rect: BuildingPlanningScreenRect,
    x: Float,
    y: Float,
    radius: Float = 0f,
): Boolean = x + radius >= rect.left && x - radius <= rect.right &&
        y + radius >= rect.top && y - radius <= rect.bottom

private data class CapturedBuilding(
    val type: UnitTypeInterface,
    val typeName: String,
    val techLevel: Int,
    val offsetX: Float,
    val offsetY: Float,
    val radius: Float,
)

private data class ActivePlanningGesture(
    val mode: BuildingPlanningMode,
    val startScreenX: Float,
    val startScreenY: Float,
    val startWorldX: Float,
    val startWorldY: Float,
    var currentScreenX: Float,
    var currentScreenY: Float,
    var currentWorldX: Float,
    var currentWorldY: Float,
)

private data class BuildingSelection(val units: List<GameUnit>, val truncated: Boolean)
private data class CapturedBlueprintSelection(val items: List<CapturedBuilding>, val truncated: Boolean)
private data class CapturedBlueprintSource(
    val type: UnitTypeInterface,
    val typeName: String,
    val techLevel: Int,
    val worldX: Float,
    val worldY: Float,
    val radius: Float,
)

/**
 * Local planning UI only. Every resulting action is a native player command; this controller
 * never creates, moves, or edits a simulation unit directly.
 */
internal object BuildingPlanningController {
    @Volatile
    var snapshot: BuildingPlanningSnapshot = BuildingPlanningSnapshot()
        private set

    private var mode = BuildingPlanningMode.OFF
    private var builderIds: List<Long> = emptyList()
    private var capturedBlueprint: List<CapturedBuilding> = emptyList()
    private var gesture: ActivePlanningGesture? = null
    private var blocked = false
    private var awaitPointerRelease = false
    private var panPointer: Pair<Float, Float>? = null
    private var desktopButton = -1
    private var desktopDown = false
    private var desktopPoint: Pair<Float, Float>? = null

    fun hasDesktopMouseCapture(): Boolean = desktopButton >= 0

    fun wantsDesktopMouse(): Boolean =
        desktopButton >= 0 || (!blocked && mode != BuildingPlanningMode.OFF && mode != BuildingPlanningMode.READY)

    /** phase: 0 press, 1 drag, 2 release; Slick buttons: 0 primary, 1 secondary. */
    fun onDesktopMouse(phase: Int, button: Int, x: Float, y: Float): Boolean {
        val engine = GameEngine.getInstance()
        if (phase == 0) {
            if (blocked || mode == BuildingPlanningMode.OFF || button !in 0..1 ||
                button != planningMouseButton(engine)
            ) return false
            if (mode == BuildingPlanningMode.READY) return false
            if (!isWorldPoint(engine, x, y)) return false
            desktopButton = button
            desktopPoint = x to y
            desktopDown = true
            awaitPointerRelease = false
        } else {
            if (desktopButton < 0 || (phase == 2 && button != desktopButton)) return false
            desktopPoint = x to y
            desktopDown = phase != 2
        }
        // The other mouse button remains native: the game already uses it for orders or camera pan.
        handleNativeDragSelection()
        if (phase == 2) {
            desktopButton = -1
            desktopPoint = null
        }
        return true
    }

    private fun isWorldPoint(engine: GameEngine, x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x < engine.gameAreaWidth && y < engine.screenHeight &&
                runCatching { engine.minimap.minimapToWorld(x, y) == null }.getOrDefault(true)

    private var suppressMoveOrder = false
    private var suppressMoveOrderForReleaseFrame = false
    private var selectionGesture = false
    private var pendingSelectionStart: Pair<Float, Float>? = null
    private var reclaimCompleted = false
    private var lastActionCount = 0
    private var statusKey: String? = null
        set(value) {
            field = value
            reportedStatusIdentity = null
        }
    private var previousMap: Any? = null
    private var previousPlayerTeam: Any? = null
    private var reportedStatusIdentity: String? = null

    /** Toggle planning with Alt+B. Selecting a builder first is required. */
    fun toggleMode() {
        GameScope.launch(GameMainDispatcher) { toggleModeOnGameThread() }
    }

    /** Start a native left-button/touch drag to capture existing and unfinished buildings. */
    fun beginBlueprintSelection() {
        GameScope.launch(GameMainDispatcher) {
            if (!ensureBuilderGroup()) return@launch
            gesture = null
            mode = BuildingPlanningMode.SELECT_BLUEPRINT
            statusKey = "rwpp.planning.blueprintDrag"
            publish()
        }
    }

    /** Arms a new primary-button drag after the HUD activation click has been released. */
    fun beginReclaimSelection() {
        GameScope.launch(GameMainDispatcher) {
            if (!ensureBuilderGroup()) return@launch
            gesture = null
            mode = BuildingPlanningMode.RECLAIM_AREA
            reclaimCompleted = false
            statusKey = "rwpp.planning.reclaimDrag"
            publish()
        }
    }

    /** Called by the game-frame hook on GameMainDispatcher. */
    fun onGameUpdate(blocked: Boolean) {
        this.blocked = blocked
        if (blocked) {
            if (gesture != null || mode == BuildingPlanningMode.RECLAIM_AREA) {
                gesture = null
                pendingSelectionStart = null
                selectionGesture = false
                if (mode == BuildingPlanningMode.RECLAIM_AREA) mode = BuildingPlanningMode.READY
            }
            suppressMoveOrder = false
            suppressMoveOrderForReleaseFrame = false
            publish()
            return
        }

        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return
        if (previousMap !== engine.map || previousPlayerTeam !== engine.playerTeam) {
            previousMap = engine.map
            previousPlayerTeam = engine.playerTeam
            clearForSessionChange()
        }
        suppressMoveOrder = false
        suppressMoveOrderForReleaseFrame = false

        val currentSelection = selectedBuildCapableUnits(engine)
        if (mode == BuildingPlanningMode.OFF || mode == BuildingPlanningMode.READY) {
            builderIds = currentSelection.map { it.id }.distinct().toList()
        } else {
            builderIds = resolveBuilders(engine, builderIds).map { it.id }
            if (builderIds.isEmpty()) {
                gesture = null
                mode = BuildingPlanningMode.OFF
                statusKey = "rwpp.planning.builderRequired"
            }
        }
        publish()
    }

    /** Called only from the native BattleInterface drag-selection render/input method. */
    internal fun handleNativeDragSelection(): Boolean {
        if (blocked) return false
        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return false
        val interfaceAccess = engine.interfaceEngine as? NativeInterfaceAccess ?: return false

        if (previousMap !== engine.map || previousPlayerTeam !== engine.playerTeam) {
            previousMap = engine.map
            previousPlayerTeam = engine.playerTeam
            clearForSessionChange()
            return false
        }

        if (awaitPointerRelease) {
            if (!isSelectionPointerDown(engine)) awaitPointerRelease = false
            return false
        }
        if (desktopButton >= 0 && desktopButton != planningMouseButton(engine)) return false
        if (desktopButton < 0 && mode != BuildingPlanningMode.OFF && mode != BuildingPlanningMode.READY && !reclaimCompleted && engine.isMouseButtonDown(
                2
            )
        ) {
            val point = currentPointer(engine)
            if (point != null && (panPointer != null || isPointerInGameArea(
                    engine,
                    interfaceAccess,
                    point.first,
                    point.second
                ))
            ) {
                val previous = panPointer
                // With right-button orders enabled native input does not pan this button.
                // Otherwise let the native camera drag own it to avoid applying the delta twice.
                if (previous != null && MouseControlPreferencesController.nativeMouseOrders(engine.settings.mouseOrders) == 2) {
                    val zoom = engine.zoomScale.coerceAtLeast(0.01f)
                    engine.setCamera(
                        engine.cameraWorldX - (point.first - previous.first) / zoom,
                        engine.cameraWorldY - (point.second - previous.second) / zoom
                    )
                    engine.clampCamera()
                }
                panPointer = point
            }
            return false
        }
        panPointer = null
        val liveBuilders = resolveBuilders(engine, builderIds)
        if (liveBuilders.isEmpty()) return false

        val activeMode = mode
        if (activeMode == BuildingPlanningMode.OFF || activeMode == BuildingPlanningMode.READY) return false

        val pointer = currentPointer(engine)
        val pointerAllowed =
            pointer != null && isPointerInGameArea(engine, interfaceAccess, pointer.first, pointer.second)
        if (activeMode == BuildingPlanningMode.PLACE_BLUEPRINT) {
            if (pointerAllowed && pointer != null) {
                val world = screenToWorld(engine, pointer.first, pointer.second)
                drawBlueprintPreview(engine, capturedBlueprint, world.first, world.second)
            }
            return processClickOrDrag(engine, interfaceAccess, liveBuilders, pointer, pointerAllowed)
        }

        if (activeMode != BuildingPlanningMode.SELECT_BLUEPRINT &&
            activeMode != BuildingPlanningMode.RECLAIM_AREA
        ) return false
        return processSelectionGesture(engine, interfaceAccess, liveBuilders, pointer, pointerAllowed)
    }

    internal fun shouldSuppressNativeMoveOrder(): Boolean =
        !blocked && (suppressMoveOrder || suppressMoveOrderForReleaseFrame)

    private fun toggleModeOnGameThread() {
        if (mode == BuildingPlanningMode.OFF) {
            if (!ensureBuilderGroup()) return
            mode = BuildingPlanningMode.READY
            statusKey = "rwpp.planning.enabled"
        } else {
            gesture = null
            pendingSelectionStart = null
            suppressMoveOrder = false
            suppressMoveOrderForReleaseFrame = false
            mode = BuildingPlanningMode.OFF
            statusKey = "rwpp.planning.disabled"
        }
        publish()
    }

    private fun ensureBuilderGroup(): Boolean {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull()
        if (engine != null) {
            if (previousMap !== engine.map || previousPlayerTeam !== engine.playerTeam) {
                previousMap = engine.map
                previousPlayerTeam = engine.playerTeam
                clearForSessionChange()
            }
            // Resolve the selection at activation, rather than reusing an earlier frame's group.
            var selected = selectedBuildCapableUnits(engine)
            if (selected.isEmpty()) {
                val zoom = engine.zoomScale.coerceAtLeast(0.01f)
                selected = nativeUnitSnapshot().filterIsInstance<OrderableUnit>().filter {
                    !it.dead && it.team === engine.playerTeam && it.transportedBy == null &&
                            it.attachedToUnit == null && it.isBuilder() && hasNativeBuildCapability(it) &&
                            io.github.rwpp.logic.selection.intersectsSelectionViewport(
                                it.x,
                                it.y - it.height,
                                it.radius,
                                engine.cameraRenderX,
                                engine.cameraRenderY,
                                engine.gameAreaWidth / zoom,
                                engine.screenHeight / zoom
                            )
                }
                if (selected.isNotEmpty()) {
                    engine.interfaceEngine.clearUnitSelection()
                    selected.forEach(engine.interfaceEngine::selectUnit)
                }
            }
            builderIds = selected.map { it.id }.distinct()
            if (builderIds.isNotEmpty()) {
                engine.interfaceEngine.cancelActiveAction()
                awaitPointerRelease = true
                pendingSelectionStart = null
                selectionGesture = false
            }
        }
        if (builderIds.isEmpty()) {
            statusKey = "rwpp.planning.builderRequired"
            mode = BuildingPlanningMode.OFF
            publish()
            return false
        }
        return true
    }

    private fun processSelectionGesture(
        engine: GameEngine,
        interfaceAccess: NativeInterfaceAccess,
        builders: List<OrderableUnit>,
        pointer: Pair<Float, Float>?,
        pointerAllowed: Boolean,
    ): Boolean {
        val currentMode = mode
        val active = gesture
        if (active != null) {
            if (isGesturePointerDown(engine, active)) {
                if (selectionGesture) suppressMoveOrder = true
                updateGesture(engine)
                val rect = planningScreenRect(
                    active.startScreenX,
                    active.startScreenY,
                    active.currentScreenX,
                    active.currentScreenY
                )
                drawPlanningRect(
                    engine,
                    rect,
                    if (currentMode == BuildingPlanningMode.RECLAIM_AREA) RECLAIM_PAINT else BLUEPRINT_PAINT
                )
                publish(rect)
            } else {
                if (pointer != null) updateGestureToPoint(engine, pointer.first, pointer.second)
                finishGesture(engine, builders)
                suppressMoveOrderForReleaseFrame = selectionGesture
                suppressMoveOrder = false
                selectionGesture = false
                gesture = null
                if (currentMode == BuildingPlanningMode.RECLAIM_AREA) {
                    reclaimCompleted = true
                    mode = BuildingPlanningMode.READY
                    pendingSelectionStart = null
                    suppressMoveOrderForReleaseFrame = false
                }
                publish()
            }
            return true
        }

        if (!isSelectionPointerDown(engine)) {
            pendingSelectionStart = null
            return false
        }
        if (!pointerAllowed || pointer == null) return false
        val start = pendingSelectionStart
        if (start == null) {
            pendingSelectionStart = pointer
            return true
        }
        val threshold = PLANNING_DRAG_THRESHOLD_DIP * engine.dipScale.coerceAtLeast(1f)
        if (distanceSquared(
                start.first,
                start.second,
                pointer.first,
                pointer.second
            ) < threshold * threshold
        ) return true
        pendingSelectionStart = null
        startGesture(engine, currentMode, start.first, start.second)
        selectionGesture = true
        suppressMoveOrder = true
        updateGesture(engine)
        publish(planningScreenRect(start.first, start.second, pointer.first, pointer.second))
        return true
    }

    private fun processClickOrDrag(
        engine: GameEngine,
        interfaceAccess: NativeInterfaceAccess,
        builders: List<OrderableUnit>,
        pointer: Pair<Float, Float>?,
        pointerAllowed: Boolean,
    ): Boolean {
        val active = gesture
        if (active != null) {
            if (isSelectionPointerDown(engine)) {
                updateGesture(engine)
            } else {
                val point = pointer ?: active.currentScreenX to active.currentScreenY
                val world = screenToWorld(engine, point.first, point.second)
                issueBlueprint(engine, builders, world.first, world.second)
                gesture = null
                mode = BuildingPlanningMode.READY
                suppressMoveOrderForReleaseFrame = false
                publish()
            }
            return true
        }

        if (pointerAllowed && pointer != null && isSelectionPointerDown(engine) &&
            interfaceAccess.rwppActiveSpecialAction() == null
        ) {
            startGesture(engine, BuildingPlanningMode.PLACE_BLUEPRINT, pointer.first, pointer.second)
            updateGesture(engine)
            return true
        }
        return false
    }

    private fun startGesture(engine: GameEngine, gestureMode: BuildingPlanningMode, screenX: Float, screenY: Float) {
        val world = screenToWorld(engine, screenX, screenY)
        gesture = ActivePlanningGesture(
            mode = gestureMode,
            startScreenX = screenX,
            startScreenY = screenY,
            startWorldX = world.first,
            startWorldY = world.second,
            currentScreenX = screenX,
            currentScreenY = screenY,
            currentWorldX = world.first,
            currentWorldY = world.second,
        )
    }

    private fun updateGesture(engine: GameEngine) {
        val pointer = currentPointer(engine) ?: return
        updateGestureToPoint(engine, pointer.first, pointer.second)
    }

    private fun updateGestureToPoint(engine: GameEngine, screenX: Float, screenY: Float) {
        val active = gesture ?: return
        val world = screenToWorld(engine, screenX, screenY)
        active.currentScreenX = screenX
        active.currentScreenY = screenY
        active.currentWorldX = world.first
        active.currentWorldY = world.second
    }

    private fun finishGesture(
        engine: GameEngine,
        builders: List<OrderableUnit>,
    ) {
        val active = gesture ?: return
        val worldRect = buildingPlanningNormalizedRect(
            active.startWorldX, active.startWorldY, active.currentWorldX, active.currentWorldY
        ) ?: return
        val threshold = PLANNING_DRAG_THRESHOLD_DIP * engine.dipScale.coerceAtLeast(1f)
        if (distanceSquared(
                active.startScreenX,
                active.startScreenY,
                active.currentScreenX,
                active.currentScreenY
            ) < threshold * threshold
        ) {
            statusKey = "rwpp.planning.dragLarger"
            return
        }
        when (active.mode) {
            BuildingPlanningMode.SELECT_BLUEPRINT -> {
                val captured = captureBlueprint(engine, worldRect)
                capturedBlueprint = captured.items
                if (captured.items.isEmpty()) {
                    statusKey = "rwpp.planning.noBuildings"
                    mode = BuildingPlanningMode.READY
                } else {
                    mode = BuildingPlanningMode.PLACE_BLUEPRINT
                    statusKey = if (captured.truncated) "rwpp.planning.blueprintLimit"
                    else "rwpp.planning.blueprintCaptured"
                }
                publish()
            }

            BuildingPlanningMode.RECLAIM_AREA -> {
                val targets = collectBuildings(engine, worldRect)
                lastActionCount = 0
                lastActionCount = issueReclaim(engine, builders, targets.units)
                statusKey = when {
                    lastActionCount == 0 -> "rwpp.planning.noReclaimTargets"
                    targets.truncated -> "rwpp.planning.reclaimLimit"
                    else -> "rwpp.planning.reclaimed"
                }
                publish()
            }

            else -> Unit
        }
    }

    private fun captureBlueprint(engine: GameEngine, rect: BuildingPlanningScreenRect): CapturedBlueprintSelection {
        val sources = ArrayList<CapturedBlueprintSource>()
        val selected = matchingBuildings(engine, rect)
        selected.forEach { unit ->
            val type = runCatching { unit.unitTypeInterface }.getOrNull() ?: return@forEach
            val name = runCatching { type.internalName }.getOrNull()?.takeIf(String::isNotBlank)
                ?: return@forEach
            sources += CapturedBlueprintSource(
                type = type,
                typeName = name,
                techLevel = runCatching { unit.getTechLevel() }.getOrDefault(1),
                worldX = unit.x,
                worldY = unit.y,
                radius = unit.radius.coerceAtLeast(12f),
            )
        }
        val queuedBlueprints = activeNativeBlueprints().mapNotNull { blueprint ->
            val type = runCatching { blueprint.unitType }.getOrNull() ?: return@mapNotNull null
            val name = runCatching { type.internalName }.getOrNull()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (blueprint.removed || !blueprint.isBuild ||
                (blueprint.ownerTeam !== engine.playerTeam && blueprint.team !== engine.playerTeam) ||
                !buildingPlanningRectTouchesPoint(rect, blueprint.x, blueprint.y, 12f)
            ) return@mapNotNull null
            CapturedBlueprintSource(
                type = type,
                typeName = name,
                techLevel = blueprint.f,
                worldX = blueprint.x,
                worldY = blueprint.y,
                radius = 12f,
            )
        }
        val unique = (sources + queuedBlueprints).sortedWith(
            compareBy<CapturedBlueprintSource>({ it.worldY }, { it.worldX }, { it.typeName }, { it.techLevel })
        ).fold(ArrayList<CapturedBlueprintSource>()) { result, candidate ->
            if (result.none { earlier ->
                    earlier.typeName == candidate.typeName && earlier.techLevel == candidate.techLevel &&
                            abs(earlier.worldX - candidate.worldX) <= 2f && abs(earlier.worldY - candidate.worldY) <= 2f
                }
            ) result += candidate
            result
        }
        val truncated = unique.size > MAX_PLANNING_TARGETS
        val bounded = unique.take(MAX_PLANNING_TARGETS)
        if (bounded.isEmpty()) return CapturedBlueprintSelection(emptyList(), truncated)
        val originX = bounded.minOf { it.worldX }
        val originY = bounded.minOf { it.worldY }
        return CapturedBlueprintSelection(
            bounded.map { source ->
                CapturedBuilding(
                    source.type, source.typeName, source.techLevel,
                    source.worldX - originX, source.worldY - originY, source.radius,
                )
            },
            truncated,
        )
    }

    private fun matchingBuildings(engine: GameEngine, rect: BuildingPlanningScreenRect): List<GameUnit> =
        nativeUnitSnapshot().asSequence()
            .filter { !it.dead && it.team === engine.playerTeam && runCatching { it.isBuilding() }.getOrDefault(false) }
            .filter { buildingPlanningRectTouchesPoint(rect, it.x, it.y, it.radius.coerceAtLeast(0f)) }
            .toList()

    private fun collectBuildings(engine: GameEngine, rect: BuildingPlanningScreenRect): BuildingSelection {
        val units = matchingBuildings(engine, rect).distinctBy { unit ->
            "${runCatching { unit.unitTypeInterface?.internalName }.getOrNull()}:${unit.getTechLevel()}:${unit.x.toInt()}:${unit.y.toInt()}"
        }.sortedWith(compareBy<GameUnit>({ it.y }, { it.x }, { it.id }))
        return BuildingSelection(units.take(MAX_PLANNING_TARGETS), units.size > MAX_PLANNING_TARGETS)
    }

    private fun activeNativeBlueprints(): List<Blueprint> = runCatching {
        val list: DeferredArrayList = Blueprint.activeBlueprints
        val items = list.items()
        val count = list.size.coerceIn(0, items.size)
        List(count) { items[it] as? Blueprint }.filterNotNull()
    }.getOrDefault(emptyList())

    private fun issueReclaim(
        engine: GameEngine,
        builders: List<OrderableUnit>,
        targets: List<GameUnit>,
    ): Int {
        if (!canIssueNativeCommands(engine)) return 0
        val access = engine.interfaceEngine as? NativeInterfaceAccess ?: return 0
        var issued = 0
        val reserved = mutableMapOf<Long, Int>()
        targets.take(MAX_PLANNING_TARGETS).forEach { target ->
            val capable = builders.filter { builder ->
                !builder.dead && builder.team === engine.playerTeam &&
                        builder.waypointCount + (reserved[builder.id] ?: 0) < MAX_PLANNING_TARGETS &&
                        runCatching { builder.canReclaimUnit(target) }.getOrDefault(false)
            }
            if (capable.isEmpty()) return@forEach
            val command = runCatching { access.rwppCreatePlanningCommand() }.getOrNull() ?: return@forEach
            if (!configurePlanningCommand(command, capable)) return@forEach
            runCatching {
                command.setReclaimWaypoint(target)
                command.queueOrder = true
            }.onFailure { return@forEach }
            capable.forEach { reserved[it.id] = (reserved[it.id] ?: 0) + 1 }
            issued++
        }
        return issued
    }

    private fun issueBlueprint(
        engine: GameEngine,
        builders: List<OrderableUnit>,
        originX: Float,
        originY: Float,
    ) {
        if (!canIssueNativeCommands(engine)) {
            lastActionCount = 0
            statusKey = "rwpp.planning.cannotPlace"
            return
        }
        val access = engine.interfaceEngine as? NativeInterfaceAccess ?: return
        val placements = buildingPlanningWorldPlacements(
            capturedBlueprint.map {
                BuildingPlanningRelativeItem(it, it.techLevel, it.offsetX, it.offsetY, it.radius)
            },
            originX, originY, MAX_PLANNING_TARGETS,
        )
        var issued = 0
        val reserved = mutableMapOf<Long, Int>()
        placements.forEach { placement ->
            val capable = builders.filter { builder ->
                !builder.dead && builder.team === engine.playerTeam && (builder.canBeGivenBuildOrders() || isLocalSandboxEditor(
                    builder
                )) &&
                        builder.waypointCount + (reserved[builder.id] ?: 0) < MAX_PLANNING_TARGETS &&
                        findNativeBuildAction(builder, placement.value.type, placement.techLevel) != null
            }
            if (capable.isEmpty()) return@forEach
            val command = runCatching { access.rwppCreatePlanningCommand() }.getOrNull() ?: return@forEach
            if (!configurePlanningCommand(command, capable)) return@forEach
            runCatching {
                command.setBuildWaypoint(placement.x, placement.y, placement.value.type, placement.techLevel)
                command.queueOrder = true
                // Native local sandbox editor uses the engine's spawn system command.
                if (capable.any(::isLocalSandboxEditor)) {
                    command.hasSystemAction = true
                    command.systemActionId = 5
                }
            }.onFailure { return@forEach }
            capable.forEach { reserved[it.id] = (reserved[it.id] ?: 0) + 1 }
            issued++
        }
        lastActionCount = issued
        statusKey = when {
            issued == 0 -> "rwpp.planning.noCompatibleBuilders"
            issued < placements.size -> "rwpp.planning.placedPartial"
            else -> "rwpp.planning.placed"
        }
    }

    private fun selectedBuildCapableUnits(engine: GameEngine): List<OrderableUnit> {
        // The battle panel caches its action selection until its next render; use live UI selection.
        val selected = engine.interfaceEngine.selectedUnits
        return selected.filterIsInstance<OrderableUnit>()
            .filter { !it.dead && it.team === engine.playerTeam && hasNativeBuildCapability(it) }
            .distinctBy { it.id }
    }

    private fun resolveBuilders(engine: GameEngine, ids: List<Long>): List<OrderableUnit> =
        ids.distinct().mapNotNull { id ->
            runCatching { GameObject.getOrderableUnitFromId(id, true) }.getOrNull()?.takeIf {
                it.id == id && !it.dead && it.team === engine.playerTeam && hasNativeBuildCapability(it)
            }
        }

    private fun hasNativeBuildCapability(unit: OrderableUnit): Boolean {
        if (isLocalSandboxEditor(unit)) return true
        if (!unit.canBeGivenBuildOrders()) return false
        if (runCatching { unit.isBuilder() }.getOrDefault(false)) return true
        return runCatching {
            unit.getSpecialActions().any { action ->
                val nativeAction = action as? SpecialAction
                nativeAction != null && nativeAction.actionType == ActionType.placeBuilding && nativeAction.unitType != null
            }
        }.getOrDefault(false)
    }

    private fun isLocalSandboxEditor(unit: OrderableUnit): Boolean =
        unit is com.corrodinggames.rts.game.units.EditorUnit &&
                io.github.rwpp.game.sandbox.sandboxUnitDataUiAllowed(GameEngine.getInstance())

    private fun findNativeBuildAction(
        unit: OrderableUnit,
        type: UnitTypeInterface,
        techLevel: Int,
    ): SpecialAction? {
        // Unit.findActionForUnitType() is a null-returning base stub for native builders.
        // The native build menu, including mod units, is the authoritative capability list.
        return unit.specialActions.filterIsInstance<SpecialAction>().firstOrNull { action ->
            action.actionType == ActionType.placeBuilding &&
                    action.unitType?.let { it === type || it.internalName == type.internalName } == true &&
                    action.getTechLevel() == techLevel &&
                    (isLocalSandboxEditor(unit) || io.github.rwpp.game.hud.hudActionUnlocked(action, unit))
        }
    }

    private fun configurePlanningCommand(command: CommandControllerCommand, units: List<OrderableUnit>): Boolean {
        val access = command as? NativeBuildingPlanningCommandAccess ?: return false
        return runCatching {
            access.rwppPlanningCommandUnits().clear()
            access.rwppPlanningPendingUnitIds().clear()
            units.forEach(command::addUnit)
        }.isSuccess
    }

    private fun nativeUnitSnapshot(): List<GameUnit> = runCatching {
        val list = GameUnit.unitList
        val items = list.items()
        val count = list.size.coerceIn(0, items.size)
        List(count) { items[it] as? GameUnit }.filterNotNull()
    }.getOrDefault(emptyList())

    private fun canIssueNativeCommands(engine: GameEngine): Boolean =
        engine.playerTeam != null && !engine.replayEngine.isPlayingReplay

    private fun isSelectionPointerDown(engine: GameEngine): Boolean =
        if (desktopButton >= 0) desktopButton == planningMouseButton(engine) && desktopDown
        else runCatching {
            val nativeOrders = MouseControlPreferencesController.nativeMouseOrders(engine.settings.mouseOrders)
            val selectionButton = if (nativeOrders == 2) 2 else 1
            engine.isMouseButtonDown(selectionButton) || (!GameEngine.isDesktopVersion() && engine.hasTouchEvents())
        }.getOrDefault(false)

    private fun planningMouseButton(engine: GameEngine): Int =
        MouseControlPreferencesController.planningSlickButton(
            nativeMouseOrders = engine.settings.mouseOrders,
            nativeMousePlacement = engine.settings.mousePlacement,
            blueprintPlacement = mode == BuildingPlanningMode.PLACE_BLUEPRINT,
        )

    private fun isGesturePointerDown(engine: GameEngine, active: ActivePlanningGesture): Boolean =
        isSelectionPointerDown(engine)

    private fun currentPointer(engine: GameEngine): Pair<Float, Float>? = desktopPoint ?: runCatching {
        val index = (0 until engine.pointerCount).firstOrNull { engine.getPointerState(it) != 0 }
        if (index != null) engine.getPointerX(index) to engine.getPointerY(index)
        else engine.primaryPointerX to engine.primaryPointerY
    }.getOrNull()?.takeIf { it.first.isFinite() && it.second.isFinite() }

    private fun isPointerInGameArea(
        engine: GameEngine,
        ui: NativeInterfaceAccess,
        x: Float,
        y: Float,
    ): Boolean {
        if (desktopButton >= 0) return isWorldPoint(engine, x, y)
        if (ui.rwppActiveSpecialAction() != null || ui.rwppPointerOnMinimap() || ui.rwppPointerDownOverSidebar() ||
            ui.rwppPointerOverUiElement()
        ) return false
        if (x < 0f || y < 0f || x >= engine.gameAreaWidth || y >= engine.screenHeight) return false
        return runCatching { engine.minimap.minimapToWorld(x, y) == null }.getOrDefault(true)
    }

    private fun screenToWorld(engine: GameEngine, screenX: Float, screenY: Float): Pair<Float, Float> {
        val scale = engine.zoomScale.takeIf { it > 0f && it.isFinite() } ?: 1f
        return screenX / scale + engine.cameraRenderX to screenY / scale + engine.cameraRenderY
    }

    private fun planningScreenRect(x1: Float, y1: Float, x2: Float, y2: Float): BuildingPlanningScreenRect? =
        buildingPlanningNormalizedRect(x1, y1, x2, y2)

    private fun drawPlanningRect(engine: GameEngine, rect: BuildingPlanningScreenRect?, paint: Paint) {
        rect ?: return
        val nativeRect = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        engine.graphics.drawRectAlt(nativeRect, paint)
    }

    private fun drawBlueprintPreview(
        engine: GameEngine,
        buildings: List<CapturedBuilding>,
        originX: Float,
        originY: Float
    ) {
        if (buildings.isEmpty()) return
        val scale = engine.zoomScale.takeIf { it > 0f && it.isFinite() } ?: 1f
        buildings.take(MAX_PLANNING_TARGETS).forEach { item ->
            val screenX = (originX + item.offsetX - engine.cameraRenderX) * scale
            val screenY = (originY + item.offsetY - engine.cameraRenderY) * scale
            val half = max(5f, item.radius * scale)
            val rect = RectF(screenX - half, screenY - half, screenX + half, screenY + half)
            engine.graphics.drawRect(rect, PREVIEW_PAINT)
        }
        val cross = max(5f, 8f * engine.dipScale)
        val x = (originX - engine.cameraRenderX) * scale
        val y = (originY - engine.cameraRenderY) * scale
        engine.graphics.drawLine(x - cross, y, x + cross, y, PREVIEW_PAINT)
        engine.graphics.drawLine(x, y - cross, x, y + cross, PREVIEW_PAINT)
    }

    private fun publish(rect: BuildingPlanningScreenRect? = null) {
        val blueprint = capturedBlueprint.map {
            BuildingPlanningBlueprintItem(it.typeName, it.techLevel, it.offsetX, it.offsetY)
        }
        snapshot = BuildingPlanningSnapshot(
            enabled = mode != BuildingPlanningMode.OFF,
            mode = mode,
            selectedBuilderIds = immutableList(builderIds),
            blueprint = immutableList(blueprint),
            statusKey = statusKey,
            lastActionCount = lastActionCount,
            dragRect = rect,
        )
        showStatusIfChanged()
    }

    private fun showStatusIfChanged() {
        val key = statusKey ?: run {
            reportedStatusIdentity = null
            return
        }
        val identity = "$key:$lastActionCount:${capturedBlueprint.size}"
        if (identity == reportedStatusIdentity) return
        reportedStatusIdentity = identity
        runCatching {
            val engine = GameEngine.getInstance()
            engine.interfaceEngine.battleInterface.showScreenMessage(
                readI18n(key, lastActionCount, capturedBlueprint.size),
                60, // Native duration is ticks (~1 second), not milliseconds.
            )
        }
    }

    private fun clearForSessionChange() {
        mode = BuildingPlanningMode.OFF
        reclaimCompleted = false
        awaitPointerRelease = false
        desktopButton = -1
        desktopDown = false
        desktopPoint = null
        panPointer = null
        builderIds = emptyList()
        capturedBlueprint = emptyList()
        gesture = null
        pendingSelectionStart = null
        suppressMoveOrder = false
        suppressMoveOrderForReleaseFrame = false
        selectionGesture = false
        statusKey = null
        lastActionCount = 0
        reportedStatusIdentity = null
        publish()
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return dx * dx + dy * dy
    }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private val BLUEPRINT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 60, 205, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val RECLAIM_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 165, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val PREVIEW_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 80, 235, 175)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
}
