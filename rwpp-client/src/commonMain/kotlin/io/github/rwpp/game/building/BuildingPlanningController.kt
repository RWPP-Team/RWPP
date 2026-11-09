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
import io.github.rwpp.api.logging.logger
import io.github.rwpp.game.building.BuildingPlanningController.planningMouseButton
import io.github.rwpp.game.hotkeys.MouseControlPreferencesController
import io.github.rwpp.mixin.building.NativeBattlePinchAccess
import io.github.rwpp.mixin.building.NativeBuildingPlanningCommandAccess
import io.github.rwpp.mixin.hud.NativeInterfaceAccess
import io.github.rwpp.api.game.Game
import io.github.rwpp.appKoin
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.corrodinggames.rts.game.units.Unit as GameUnit

// allocateWaypoint() exposes 29 queued entries; slot 29 is native scratch space.
private const val MAX_PLANNING_TARGETS = 29
private const val PLANNING_DRAG_THRESHOLD_DIP = 7f

/** Two-finger boxes report no drag distance, so a world-size floor rejects accidental taps. */
private const val PLANNING_MIN_WORLD_BOX = 2f

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
    val invalidPlacements: Int = 0,
    val canConfirm: Boolean = false,
)

@Serializable
data class BuildingPlanningBlueprintItem(
    val typeInternalName: String,
    val techLevel: Int,
    val offsetX: Float,
    val offsetY: Float,
)

internal fun validBlueprintItems(items: List<BuildingPlanningBlueprintItem>): Boolean =
    items.size in 1..MAX_PLANNING_TARGETS && items.all {
        it.typeInternalName.isNotBlank() && it.typeInternalName.length <= 256 &&
                it.techLevel in 1..255 && abs(it.offsetX) <= 100000f && abs(it.offsetY) <= 100000f
    }

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

/**
 * Android reports the second touch pointer with state 2, the value the desktop uses for the
 * secondary mouse button, so touch may never enter the desktop mouse branches.
 */
internal enum class BuildingPlanningInputRoute { NATIVE, TOUCH_BOX, DESKTOP_MOUSE }

internal fun buildingPlanningInputRoute(desktop: Boolean, planningArmed: Boolean): BuildingPlanningInputRoute =
    when {
        !planningArmed -> BuildingPlanningInputRoute.NATIVE
        desktop -> BuildingPlanningInputRoute.DESKTOP_MOUSE
        else -> BuildingPlanningInputRoute.TOUCH_BOX
    }

/**
 * Native BattleInterface builds its box from exactly two pointers and ignores the gesture while
 * the three-finger pinch is still settling. The planner must accept the same gesture.
 */
/** Android retains the last pointer count after ACTION_UP; native isDown owns its lifetime. */
internal fun buildingPlanningTouchPointerCount(pointerCount: Int, down: Boolean): Int =
    if (down) pointerCount.coerceIn(0, 10) else 0

internal fun buildingPlanningHasTouchBox(pointerCount: Int, pinchTimer: Float): Boolean =
    pointerCount == 2 && pinchTimer <= 0f

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
) {
    // Dedicated native placeholders: never mutate the shared ghost used by the anchor.
    val previewUnit: OrderableUnit? by lazy {
        when (type) {
            is com.corrodinggames.rts.game.units.UnitType -> type.createUnit(true)
            is com.corrodinggames.rts.game.units.custom.CustomUnitMetadata -> type.createUnit(true)
            else -> null
        } as? OrderableUnit
    }
}

private data class ActivePlanningGesture(
    val mode: BuildingPlanningMode,
    var startScreenX: Float,
    var startScreenY: Float,
    var startWorldX: Float,
    var startWorldY: Float,
    var currentScreenX: Float,
    var currentScreenY: Float,
    var currentWorldX: Float,
    var currentWorldY: Float,
)

private data class BlueprintTouchDrag(
    val pointers: Int, val movesLayout: Boolean,
    val startX: Float, val startY: Float,
    val originX: Float, val originY: Float,
    val cameraX: Float, val cameraY: Float,
    var lastX: Float, var lastY: Float,
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
    private var touchTraceBudget = 0
    private var lastTouchTrace: String? = null
    private var placementSession: BlueprintPlacementSession<CapturedBuilding>? = null
    private var invalidPlacements = 0
    private var touchPlacementDrag: BlueprintTouchDrag? = null
    private var touchPlacementWaitForRelease = false

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

    /** A cancelled placement must not turn its own right-button release into a move order. */
    private var suppressOrderUntilButtonRelease = false
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

    /** Alt+B arms the blueprint: select an area, then place the copy. The border shows the mode. */
    fun toggleMode() {
        GameScope.launch(GameMainDispatcher) { toggleModeOnGameThread() }
    }

    /** Start a native left-button/touch drag to capture existing and unfinished buildings. */
    fun beginBlueprintSelection() {
        GameScope.launch(GameMainDispatcher) {
            awaitPointerRelease = false
            if (!ensureBuilderGroup(allowEmpty = true)) return@launch
            gesture = null
            placementSession = null
            invalidPlacements = 0
            mode = BuildingPlanningMode.SELECT_BLUEPRINT
            statusKey = "rwpp.planning.blueprintDrag"
            publish()
        }
    }

    fun loadBlueprint(items: List<BuildingPlanningBlueprintItem>) {
        if (!validBlueprintItems(items)) return
        GameScope.launch(GameMainDispatcher) {
            val engine = GameEngine.getInstance()
            if (engine.map == null || !canIssueNativeCommands(engine)) return@launch
            val types = appKoin.get<Game>().getAllUnitTypes().associateBy { it.internalName }
            val captured = items.map { item ->
                val type = types[item.typeInternalName]
                if (type == null || !type.isBuilding) {
                    statusKey = "rwpp.planning.blueprintUnavailable"
                    publish()
                    return@launch
                }
                CapturedBuilding(type, item.typeInternalName, item.techLevel, item.offsetX, item.offsetY, 12f)
            }
            if (!ensureBuilderGroup(allowEmpty = true)) return@launch
            gesture = null
            capturedBlueprint = captured
            touchPlacementDrag = null
            touchPlacementWaitForRelease = false
            placementSession = BlueprintPlacementSession(captured.map {
                BuildingPlanningRelativeItem(it, it.techLevel, it.offsetX, it.offsetY, it.radius)
            }, engine.viewCenterWorldX, engine.viewCenterWorldY)
            mode = BuildingPlanningMode.PLACE_BLUEPRINT
            statusKey = "rwpp.planning.independentMove"
            refreshIndependentPlacementValidity(engine)
            publish()
        }
    }

    /** Arms a new primary-button drag after the HUD activation click has been released. */
    fun beginReclaimSelection() {
        GameScope.launch(GameMainDispatcher) {
            awaitPointerRelease = false
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
            // A menu opened between press and release leaves the desktop capture behind. Every
            // later drag would then be cancelled before the native handler sees it.
            desktopButton = -1
            desktopDown = false
            desktopPoint = null
            suppressMoveOrder = false
            suppressMoveOrderForReleaseFrame = false
            publish()
            return
        }

        val engine = runCatching { GameEngine.getInstance() }.getOrNull() ?: return
        if (ownsIndependentPlanningInput() && engine.interfaceEngine.activeSpecialAction != null) {
            // Selecting an ordinary native action explicitly leaves the private planner.
            exitPlanning()
            return
        }
        if (mode == BuildingPlanningMode.PLACE_BLUEPRINT) {
            if (GameEngine.isDesktopVersion()) currentPointer(engine)?.let { (x, y) ->
                if (isWorldPoint(engine, x, y)) {
                    val world = screenToWorld(engine, x, y)
                    moveIndependentBlueprintTo(world.first, world.second)
                }
            }
            refreshIndependentPlacementValidity(engine)
        }
        if (previousMap !== engine.map || previousPlayerTeam !== engine.playerTeam) {
            previousMap = engine.map
            previousPlayerTeam = engine.playerTeam
            clearForSessionChange()
        }
        suppressMoveOrder = false
        suppressMoveOrderForReleaseFrame = false
        if (suppressOrderUntilButtonRelease && !isPlacementCancelButtonDown(engine)) {
            suppressOrderUntilButtonRelease = false
        }
        // The latch belongs to the gesture that armed it. Any frame without a pointer must
        // clear it, otherwise an interrupted box keeps the planner out of the input path.
        if (awaitPointerRelease && !isSelectionPointerDown(engine)) awaitPointerRelease = false

        val currentSelection = selectedBuildCapableUnits(engine)
        if (mode == BuildingPlanningMode.OFF || mode == BuildingPlanningMode.READY) {
            builderIds = currentSelection.map { it.id }.distinct().toList()
        } else {
            builderIds = resolveBuilders(engine, builderIds).map { it.id }
            if (builderIds.isEmpty() && mode != BuildingPlanningMode.RECLAIM_AREA) {
                builderIds = currentSelection.map { it.id }
            }
            if (builderIds.isEmpty() && mode == BuildingPlanningMode.RECLAIM_AREA) {
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
            traceTouchInput(engine, "activation-release")
            if (isSelectionPointerDown(engine)) return false
            awaitPointerRelease = false
        }
        if (desktopButton >= 0 && desktopButton != planningMouseButton(engine)) return false

        val activeMode = mode
        val planningArmed =
            activeMode != BuildingPlanningMode.OFF && activeMode != BuildingPlanningMode.READY
        when (buildingPlanningInputRoute(GameEngine.isDesktopVersion(), planningArmed)) {
            BuildingPlanningInputRoute.NATIVE -> return false
            BuildingPlanningInputRoute.TOUCH_BOX -> {
                // Touch preview movement never arms or submits a native placement action.
                if (activeMode == BuildingPlanningMode.PLACE_BLUEPRINT) {
                    processIndependentTouchPlacement(engine)
                    return true
                }
                // A two-finger box is the only touch gesture the planner may own. The second
                // finger also reports state 2, so the desktop branches have to stay unreachable.
                traceTouchInput(engine, "selection")
                val touchBuilders = resolveBuilders(engine, builderIds)
                if (touchBuilders.isEmpty() && activeMode == BuildingPlanningMode.RECLAIM_AREA) return false
                processTouchGesture(engine, interfaceAccess, touchBuilders)
                // Own the whole selection mode, including one-finger frames and release.
                return true
            }

            BuildingPlanningInputRoute.DESKTOP_MOUSE -> Unit
        }

        // Desktop cancels a blueprint with the opposite mouse button, mirroring the native
        // placement action. Orders stay suppressed in this mode, so the ground tap that a right
        // click would otherwise turn into a move order never reaches the simulation.
        if (activeMode == BuildingPlanningMode.PLACE_BLUEPRINT && desktopButton < 0 &&
            isPlacementCancelButtonDown(engine)
        ) {
            suppressOrderUntilButtonRelease = true
            exitPlanning()
            return true
        }

        if (planningArmed && desktopButton < 0 && !reclaimCompleted && engine.isMouseButtonDown(2)) {
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
        if (liveBuilders.isEmpty() && activeMode == BuildingPlanningMode.RECLAIM_AREA) return false

        val pointer = currentPointer(engine)
        val pointerAllowed =
            pointer != null && isPointerInGameArea(engine, interfaceAccess, pointer.first, pointer.second)
        if (activeMode == BuildingPlanningMode.PLACE_BLUEPRINT) {
            return processClickOrDrag(engine, interfaceAccess, liveBuilders, pointer, pointerAllowed)
        }

        if (activeMode != BuildingPlanningMode.SELECT_BLUEPRINT &&
            activeMode != BuildingPlanningMode.RECLAIM_AREA
        ) return false
        return processSelectionGesture(engine, interfaceAccess, liveBuilders, pointer, pointerAllowed)
    }

    internal fun shouldSuppressNativeMoveOrder(): Boolean =
        !blocked && (suppressMoveOrder || suppressMoveOrderForReleaseFrame ||
                suppressOrderUntilButtonRelease || ownsIndependentPlanningInput())

    private fun toggleModeOnGameThread() {
        if (mode != BuildingPlanningMode.OFF) {
            exitPlanning()
            return
        }
        awaitPointerRelease = false
        if (!ensureBuilderGroup(allowEmpty = true)) return
        gesture = null
        placementSession = null
        invalidPlacements = 0
        mode = BuildingPlanningMode.SELECT_BLUEPRINT
        statusKey = "rwpp.planning.blueprintDrag"
        publish()
    }

    private fun exitPlanning() {
        placementSession = null
        invalidPlacements = 0
        touchPlacementDrag = null
        touchPlacementWaitForRelease = false
        gesture = null
        pendingSelectionStart = null
        awaitPointerRelease = false
        suppressMoveOrder = false
        suppressMoveOrderForReleaseFrame = false
        mode = BuildingPlanningMode.OFF
        statusKey = "rwpp.planning.disabled"
        publish()
    }

    private fun ensureBuilderGroup(allowEmpty: Boolean = false): Boolean {
        val engine = runCatching { GameEngine.getInstance() }.getOrNull()
        if (engine != null) {
            if (previousMap !== engine.map || previousPlayerTeam !== engine.playerTeam) {
                previousMap = engine.map
                previousPlayerTeam = engine.playerTeam
                clearForSessionChange()
            }
            if (allowEmpty) engine.interfaceEngine.cancelActiveAction()
            // Resolve the selection at activation, rather than reusing an earlier frame's group.
            var selected = selectedBuildCapableUnits(engine)
            if (selected.isEmpty()) {
                val zoom = engine.zoomScale.coerceAtLeast(0.01f)
                selected = nativeUnitSnapshot().filterIsInstance<OrderableUnit>().filter {
                    !it.dead && it.team === engine.playerTeam && it.transportedBy == null &&
                            it.attachedToUnit == null && it.isBuilder && hasNativeBuildCapability(it) &&
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
        if (!GameEngine.isDesktopVersion() && engine != null) {
            touchTraceBudget = 8
            lastTouchTrace = null
            logger().info(
                "Blueprint touch activation: builders={}, down={}, pointers={}",
                builderIds.size,
                engine.hasTouchEvents(),
                engine.pointerCount
            )
        }
        if (builderIds.isEmpty() && !allowEmpty) {
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
                    mode = BuildingPlanningMode.OFF
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
                val point = pointer ?: (active.currentScreenX to active.currentScreenY)
                val world = screenToWorld(engine, point.first, point.second)
                moveIndependentBlueprintTo(world.first, world.second)
                confirmIndependentBlueprint()
                gesture = null
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

    /**
     * Native Android defines a selection box by two fingers: the first and the second pointer
     * are opposite corners, and BattleInterface ignores it while a pinch is in progress. One
     * finger therefore keeps panning the camera exactly as it does without planning.
     */
    private fun processTouchGesture(
        engine: GameEngine,
        ui: NativeInterfaceAccess,
        builders: List<OrderableUnit>,
    ): Boolean {
        val current = mode
        // Touch confirms a blueprint through the placement button; the drag box only selects areas.
        if (current == BuildingPlanningMode.PLACE_BLUEPRINT) return false
        val box = touchSelectionBox(engine)
        val active = gesture
        if (active != null) {
            // Both corners were validated when the box opened. A finger that drifts over the
            // HUD or the sidebar must not end the gesture the way it ends a native selection.
            if (box != null) {
                updateGestureToBox(engine, box)
                suppressMoveOrder = true
                val rect = planningScreenRect(
                    active.startScreenX, active.startScreenY,
                    active.currentScreenX, active.currentScreenY
                )
                drawPlanningRect(engine, rect, planningGesturePaint(current))
                publish(rect)
            } else {
                if (activeTouchPointerCount(engine) >= 3) {
                    gesture = null
                    publish()
                    return true
                }
                // Wait for both fingers to lift: the remaining finger belongs to capture,
                // and must not become a placement tap on the next native frame.
                if (activeTouchPointerCount(engine) == 1) {
                    suppressMoveOrder = true
                    drawPlanningRect(
                        engine, planningScreenRect(
                            active.startScreenX, active.startScreenY,
                            active.currentScreenX, active.currentScreenY
                        ), planningGesturePaint(current)
                    )
                    return true
                }
                // Consume the capture release before arming native placement.
                ui.rwppSetTapReleased(false)
                finishGesture(engine, builders)
                gesture = null
                suppressMoveOrder = false
                if (current == BuildingPlanningMode.RECLAIM_AREA) mode = BuildingPlanningMode.OFF
                awaitPointerRelease = true
                publish()
            }
            return true
        }
        if (box == null || !isBoxInGameArea(engine, ui, box)) return false
        startGesture(engine, current, box.first.first, box.first.second)
        updateGestureToBox(engine, box)
        suppressMoveOrder = true
        val rect = planningScreenRect(box.first.first, box.first.second, box.second.first, box.second.second)
        drawPlanningRect(engine, rect, planningGesturePaint(current))
        publish(rect)
        return true
    }

    private fun activeTouchPointerCount(engine: GameEngine): Int = runCatching {
        buildingPlanningTouchPointerCount(engine.pointerCount, engine.hasTouchEvents())
    }.getOrDefault(0)

    /** Bounded transition logs expose real touch lifetime without installing diagnostic extensions. */
    private fun traceTouchInput(engine: GameEngine, phase: String) {
        if (touchTraceBudget <= 0 || GameEngine.isDesktopVersion()) return
        val state =
            "$phase down=${engine.hasTouchEvents()} pointers=${engine.pointerCount} pinch=${battlePinchTimer(engine)}"
        if (state == lastTouchTrace) return
        lastTouchTrace = state
        touchTraceBudget--
        logger().info("Blueprint touch input: {} mode={} builders={}", state, mode, builderIds.size)
    }

    /** Returns the two finger positions that span the current selection box, if any. */
    private fun touchSelectionBox(engine: GameEngine): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val count = activeTouchPointerCount(engine)
        if (!buildingPlanningHasTouchBox(count, battlePinchTimer(engine))) return null
        val points = ArrayList<Pair<Float, Float>>(2)
        for (index in 0 until count) {
            val x = runCatching { engine.getPointerX(index) }.getOrDefault(Float.NaN)
            val y = runCatching { engine.getPointerY(index) }.getOrDefault(Float.NaN)
            if (!x.isFinite() || !y.isFinite()) continue
            points += x to y
            if (points.size == 2) break
        }
        if (points.size < 2) return null
        return points[0] to points[1]
    }

    private fun battlePinchTimer(engine: GameEngine): Float = runCatching {
        (engine.interfaceEngine.battleInterface as? NativeBattlePinchAccess)?.rwppPinchTimer() ?: 0f
    }.getOrDefault(0f)

    private fun isBoxInGameArea(
        engine: GameEngine,
        ui: NativeInterfaceAccess,
        box: Pair<Pair<Float, Float>, Pair<Float, Float>>,
    ): Boolean = isTouchPointInGameArea(engine, box.first.first, box.first.second) &&
            isTouchPointInGameArea(engine, box.second.first, box.second.second)

    /**
     * The shared UI-hit test resolves the primary pointer, which is still sitting on the HUD
     * control that armed the mode. Both fingers therefore have to be validated on their own
     * coordinates; the Compose layer already consumed anything that landed on a HUD control.
     */
    private fun isTouchPointInGameArea(engine: GameEngine, x: Float, y: Float): Boolean {
        return !(x < 0f || y < 0f || x >= engine.gameAreaWidth || y >= engine.screenHeight) && runCatching { engine.minimap.minimapToWorld(x, y) == null }.getOrDefault(true)
    }

    private fun updateGestureToBox(
        engine: GameEngine,
        box: Pair<Pair<Float, Float>, Pair<Float, Float>>,
    ) {
        val active = gesture ?: return
        val start = screenToWorld(engine, box.first.first, box.first.second)
        val end = screenToWorld(engine, box.second.first, box.second.second)
        active.startScreenX = box.first.first
        active.startScreenY = box.first.second
        active.startWorldX = start.first
        active.startWorldY = start.second
        active.currentScreenX = box.second.first
        active.currentScreenY = box.second.second
        active.currentWorldX = end.first
        active.currentWorldY = end.second
    }

    private fun planningGesturePaint(current: BuildingPlanningMode): Paint =
        if (current == BuildingPlanningMode.RECLAIM_AREA) RECLAIM_PAINT else BLUEPRINT_PAINT

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
        completeGesture(engine, builders, worldRect, active.mode)
    }

    /** Completes a gesture from a world rectangle shared by both the touch and mouse paths. */
    private fun completeGesture(
        engine: GameEngine,
        builders: List<OrderableUnit>,
        worldRect: BuildingPlanningScreenRect,
        gestureMode: BuildingPlanningMode = mode,
    ) {
        when (gestureMode) {
            BuildingPlanningMode.SELECT_BLUEPRINT -> {
                val captured = captureBlueprint(engine, worldRect)
                capturedBlueprint = captured.items
                if (!GameEngine.isDesktopVersion()) logger().info(
                    "Blueprint touch capture: buildings={}, builders={}",
                    captured.items.size,
                    builders.size
                )
                if (captured.items.isEmpty()) {
                    statusKey = "rwpp.planning.noBuildings"
                    mode = BuildingPlanningMode.SELECT_BLUEPRINT
                } else {
                    mode = BuildingPlanningMode.PLACE_BLUEPRINT
                    statusKey = if (captured.truncated) "rwpp.planning.blueprintLimit"
                    else "rwpp.planning.blueprintCaptured"
                    // Capture ends with a private layout, never a native build action.
                    engine.interfaceEngine.cancelActiveAction()
                    touchPlacementDrag = null
                    touchPlacementWaitForRelease = false
                    placementSession = BlueprintPlacementSession(captured.items.map {
                        BuildingPlanningRelativeItem(it, it.techLevel, it.offsetX, it.offsetY, it.radius)
                    }, (worldRect.left + worldRect.right) / 2f, (worldRect.top + worldRect.bottom) / 2f)
                    invalidPlacements = captured.items.size
                    statusKey = "rwpp.planning.independentMove"
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

    internal fun ownsIndependentPlanningInput(): Boolean = !blocked &&
            mode in listOf(
        BuildingPlanningMode.SELECT_BLUEPRINT,
        BuildingPlanningMode.PLACE_BLUEPRINT,
        BuildingPlanningMode.RECLAIM_AREA
    )

    internal fun suppressNativeTouchPlacementPan(): Boolean {
        if (GameEngine.isDesktopVersion() || blocked || mode != BuildingPlanningMode.PLACE_BLUEPRINT) return false
        val engine = GameEngine.getInstance()
        return activeTouchPointerCount(engine) in 1..2 && isTouchPointInGameArea(
            engine,
            engine.getPointerX(0),
            engine.getPointerY(0)
        )
    }

    private fun processIndependentTouchPlacement(engine: GameEngine) {
        val count = activeTouchPointerCount(engine)
        val session = placementSession ?: return
        if (count == 0 || count >= 3) {
            touchPlacementDrag = null
            touchPlacementWaitForRelease = count >= 3
        } else if (count == 2 || !touchPlacementWaitForRelease) {
            val x = if (count == 2) (engine.getPointerX(0) + engine.getPointerX(1)) / 2f else engine.getPointerX(0)
            val y = if (count == 2) (engine.getPointerY(0) + engine.getPointerY(1)) / 2f else engine.getPointerY(0)
            if (isTouchPointInGameArea(engine, x, y)) {
                var drag = touchPlacementDrag
                val zoom = engine.zoomScale.coerceAtLeast(0.01f)
                if (drag == null || drag.pointers != count) {
                    val world = screenToWorld(engine, x, y)
                    val onLayout =
                        count == 1 && session.contains(world.first, world.second, 12f * engine.dipScale / zoom)
                    drag = BlueprintTouchDrag(
                        count, onLayout, x, y, session.originX, session.originY,
                        engine.cameraWorldX, engine.cameraWorldY, x, y
                    )
                    touchPlacementDrag = drag
                } else if (drag.movesLayout) {
                    // Keep the grab offset; touching a building must not jump its center to the finger.
                    moveIndependentBlueprintTo(
                        drag.originX + (x - drag.startX) / zoom,
                        drag.originY + (y - drag.startY) / zoom
                    )
                } else {
                    engine.setCamera(
                        engine.cameraWorldX - (x - drag.lastX) / zoom,
                        engine.cameraWorldY - (y - drag.lastY) / zoom
                    )
                    engine.clampCamera()
                    // Empty-ground drags move the view, with the blueprint following the camera.
                    // Accumulate from the gesture start so grid snapping cannot swallow small steps.
                    moveIndependentBlueprintTo(
                        drag.originX + engine.cameraWorldX - drag.cameraX,
                        drag.originY + engine.cameraWorldY - drag.cameraY
                    )
                }
                drag.lastX = x
                drag.lastY = y
            }
            if (count == 2) touchPlacementWaitForRelease = true
        }
        (engine.interfaceEngine as? NativeInterfaceAccess)?.rwppSetTapReleased(false)
        publish()
    }

    internal fun moveIndependentBlueprintTo(x: Float, y: Float) {
        val session = placementSession ?: return
        val engine = GameEngine.getInstance()
        val reference = session.items.firstOrNull() ?: return
        val unit = reference.value.previewUnit ?: return
        session.moveTo(
            snapBlueprintOrigin(x, reference.offsetX, unit.minimapOriginX, engine.map.tileWidth.toFloat()),
            snapBlueprintOrigin(y, reference.offsetY, unit.minimapOriginY, engine.map.tileHeight.toFloat()),
        )
    }

    private fun independentPlacementAllowed(
        engine: GameEngine,
        placement: BuildingPlanningWorldPlacement<CapturedBuilding>
    ): Boolean {
        val unit = placement.value.previewUnit ?: return false
        unit.setTeamDirect(engine.playerTeam)
        unit.setTechLevel(placement.techLevel)
        return resolveBuilders(engine, builderIds).any { builder ->
            findNativeBuildAction(builder, placement.value.type, placement.techLevel) != null &&
                    engine.interfaceEngine.checkPlacementPosition(unit, placement.x, placement.y, false, false, builder)
        }
    }

    private fun refreshIndependentPlacementValidity(engine: GameEngine) {
        val placements = placementSession?.placements() ?: return
        val builders = resolveBuilders(engine, builderIds)
        val reserved = mutableMapOf<Long, Int>()
        invalidPlacements = placements.count { placement ->
            val capable = builders.filter {
                it.waypointCount + (reserved[it.id] ?: 0) < MAX_PLANNING_TARGETS &&
                        findNativeBuildAction(it, placement.value.type, placement.techLevel) != null
            }
            val allowed = capable.isNotEmpty() && independentPlacementAllowed(engine, placement)
            if (allowed) capable.forEach { reserved[it.id] = (reserved[it.id] ?: 0) + 1 }
            !allowed
        }
    }

    /** Render every captured type directly. This never calls native placement input or line expansion. */
    internal fun drawIndependentBlueprintPreview() {
        if (blocked || mode != BuildingPlanningMode.PLACE_BLUEPRINT) return
        val engine = GameEngine.getInstance()
        val session = placementSession ?: return
        val builders = resolveBuilders(engine, builderIds)
        session.placements().forEach { placement ->
            val unit = placement.value.previewUnit ?: return@forEach
            unit.setTeamDirect(engine.playerTeam)
            unit.setTechLevel(placement.techLevel)
            unit.angle = if (unit.isBuilding) -90f else 0f
            val builder =
                builders.firstOrNull { findNativeBuildAction(it, placement.value.type, placement.techLevel) != null }
            // Compact sprite rendering skips the native placement grid, which dereferences
            // activeSpecialAction. The independent mode draws its own footprint instead.
            val allowed =
                engine.interfaceEngine.checkPlacementPosition(unit, placement.x, placement.y, true, true, builder)
            unit.x = placement.x
            unit.y = placement.y
            val tile = unit.getMinimapPosition(engine.map, android.graphics.Point())
            val footprint = unit.constructionFootprint
            val left = (tile.x + footprint.left) * engine.map.tileWidth.toFloat()
            val top = (tile.y + footprint.top) * engine.map.tileHeight.toFloat()
            val right = (tile.x + footprint.right + 1) * engine.map.tileWidth.toFloat()
            val bottom = (tile.y + footprint.bottom + 1) * engine.map.tileHeight.toFloat()
            drawPlanningRect(
                engine, worldRectToScreenRect(engine, BuildingPlanningScreenRect(left, top, right, bottom)),
                if (allowed && builder != null) BLUEPRINT_PAINT else BLOCKED_BLUEPRINT_PAINT
            )
        }
        refreshIndependentPlacementValidity(engine)
        publish()
    }

    fun matchBlueprintBuilders() {
        GameScope.launch(GameMainDispatcher) {
            if (mode != BuildingPlanningMode.PLACE_BLUEPRINT) return@launch
            val engine = GameEngine.getInstance()
            val session = placementSession ?: return@launch
            val candidates = nativeUnitSnapshot().filterIsInstance<OrderableUnit>().filter {
                !it.dead && it.team === engine.playerTeam && it.transportedBy == null &&
                        it.attachedToUnit == null && hasNativeBuildCapability(it)
            }
            val matched = session.items.mapNotNull { item ->
                candidates.filter { findNativeBuildAction(it, item.value.type, item.techLevel) != null }
                    .minByOrNull { distanceSquared(it.x, it.y, session.originX, session.originY) }
            }.distinctBy { it.id }
            builderIds = matched.map { it.id }
            engine.interfaceEngine.clearUnitSelection()
            matched.forEach(engine.interfaceEngine::selectUnit)
            refreshIndependentPlacementValidity(engine)
            publish()
        }
    }

    fun confirmBlueprint() {
        GameScope.launch(GameMainDispatcher) { confirmIndependentBlueprint() }
    }

    fun cancelBlueprint() {
        GameScope.launch(GameMainDispatcher) { exitPlanning() }
    }

    private fun confirmIndependentBlueprint() {
        if (blocked || mode != BuildingPlanningMode.PLACE_BLUEPRINT) return
        val engine = GameEngine.getInstance()
        val session = placementSession ?: return
        refreshIndependentPlacementValidity(engine)
        if (invalidPlacements != 0) {
            statusKey = "rwpp.planning.independentBlocked"
            publish()
            return
        }
        val builders = resolveBuilders(engine, builderIds)
        issueBlueprint(engine, builders)
        if (lastActionCount != session.items.size) {
            publish()
            return
        }
        logger().info("Blueprint independent submit: captured={}, issued={}", session.items.size, lastActionCount)
        placementSession = null
        gesture = null
        mode = BuildingPlanningMode.OFF
        publish()
    }

    private fun startGestureFromWorld(
        engine: GameEngine,
        gestureMode: BuildingPlanningMode,
        rect: BuildingPlanningScreenRect,
    ) {
        val screen = worldRectToScreenRect(engine, rect)
        gesture = ActivePlanningGesture(
            mode = gestureMode,
            startScreenX = screen?.left ?: 0f,
            startScreenY = screen?.top ?: 0f,
            startWorldX = rect.left,
            startWorldY = rect.top,
            currentScreenX = screen?.right ?: 0f,
            currentScreenY = screen?.bottom ?: 0f,
            currentWorldX = rect.right,
            currentWorldY = rect.bottom,
        )
    }

    private fun updateGestureFromWorld(engine: GameEngine, rect: BuildingPlanningScreenRect) {
        val active = gesture ?: return
        active.startWorldX = rect.left
        active.startWorldY = rect.top
        active.currentWorldX = rect.right
        active.currentWorldY = rect.bottom
        val screen = worldRectToScreenRect(engine, rect) ?: return
        active.startScreenX = screen.left
        active.startScreenY = screen.top
        active.currentScreenX = screen.right
        active.currentScreenY = screen.bottom
    }

    private fun worldRectToScreenRect(
        engine: GameEngine,
        rect: BuildingPlanningScreenRect,
    ): BuildingPlanningScreenRect? {
        val scale = engine.zoomScale.takeIf { it > 0f && it.isFinite() } ?: 1f
        return buildingPlanningNormalizedRect(
            (rect.left - engine.cameraRenderX) * scale,
            (rect.top - engine.cameraRenderY) * scale,
            (rect.right - engine.cameraRenderX) * scale,
            (rect.bottom - engine.cameraRenderY) * scale,
        )
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
                techLevel = runCatching { unit.techLevel }.getOrDefault(1),
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
            compareBy({ it.worldY }, { it.worldX }, { it.typeName }, { it.techLevel })
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
            .filter { !it.dead && it.team === engine.playerTeam && runCatching { it.isBuilding }.getOrDefault(false) }
            .filter { buildingPlanningRectTouchesPoint(rect, it.x, it.y, it.radius.coerceAtLeast(0f)) }
            .toList()

    private fun collectBuildings(engine: GameEngine, rect: BuildingPlanningScreenRect): BuildingSelection {
        val units = matchingBuildings(engine, rect).distinctBy { unit ->
            "${runCatching { unit.unitTypeInterface?.internalName }.getOrNull()}:${unit.techLevel}:${unit.x.toInt()}:${unit.y.toInt()}"
        }.sortedWith(compareBy({ it.y }, { it.x }, { it.id }))
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

    private fun issueBlueprint(engine: GameEngine, builders: List<OrderableUnit>) {
        lastActionCount = 0
        if (!canIssueNativeCommands(engine)) {
            statusKey = "rwpp.planning.cannotPlace"
            return
        }
        val access = engine.interfaceEngine as? NativeInterfaceAccess ?: return
        val placements = placementSession?.placements() ?: return
        val created = ArrayList<CommandControllerCommand>()
        val reserved = mutableMapOf<Long, Int>()
        var configured = 0
        for ((value, techLevel, x, y) in placements) {
            val capable = builders.filter { builder ->
                !builder.dead && builder.team === engine.playerTeam &&
                        (builder.canBeGivenBuildOrders() || isLocalSandboxEditor(builder)) &&
                        builder.waypointCount + (reserved[builder.id] ?: 0) < MAX_PLANNING_TARGETS &&
                        findNativeBuildAction(builder, value.type, techLevel) != null
            }
            if (capable.isEmpty()) break
            val command = runCatching { access.rwppCreatePlanningCommand() }.getOrNull() ?: break
            created += command
            if (!configurePlanningCommand(command, capable)) break
            val success = runCatching {
                command.setBuildWaypoint(x, y, value.type, techLevel)
                command.queueOrder = true
                if (capable.any(::isLocalSandboxEditor)) {
                    command.hasSystemAction = true
                    command.systemActionId = 5
                }
            }.isSuccess
            if (!success) break
            capable.forEach { reserved[it.id] = (reserved[it.id] ?: 0) + 1 }
            configured++
        }
        if (configured != placements.size) {
            // Nothing dispatches until this game-thread callback finishes. Roll back only our
            // own newly created batch if any item fails; unrelated native orders stay intact.
            val owned = Collections.newSetFromMap(IdentityHashMap<CommandControllerCommand, Boolean>())
                .apply { addAll(created) }
            engine.commandController.localPendingCommands.removeAll { it in owned }
            engine.commandController.networkPendingCommands.removeAll { it in owned }
            statusKey = "rwpp.planning.independentBlocked"
            return
        }
        lastActionCount = configured
        statusKey = "rwpp.planning.placed"
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
        if (runCatching { unit.isBuilder }.getOrDefault(false)) return true
        return runCatching {
            unit.specialActions.any { action ->
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
                    action.techLevel == techLevel &&
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
            // A touch gesture reports every finger through the same pointer-state array the
            // desktop uses for mouse buttons, so touch has to be judged by pointer count.
            if (!GameEngine.isDesktopVersion()) return@runCatching engine.hasTouchEvents()
            val nativeOrders = MouseControlPreferencesController.nativeMouseOrders(engine.settings.mouseOrders)
            val selectionButton = if (nativeOrders == 2) 2 else 1
            engine.isMouseButtonDown(selectionButton)
        }.getOrDefault(false)

    /**
     * Native placement cancels with the opposite button. The placement mapping is read directly
     * instead of through [planningMouseButton], which switches back to the selection button as soon
     * as the planner leaves the placement mode.
     */
    private fun isPlacementCancelButtonDown(engine: GameEngine): Boolean {
        if (!GameEngine.isDesktopVersion()) return false
        if (!runCatching { engine.settings.mouseSupport }.getOrDefault(false)) return false
        val confirm = MouseControlPreferencesController.planningSlickButton(
            nativeMouseOrders = engine.settings.mouseOrders,
            nativeMousePlacement = engine.settings.mousePlacement,
            blueprintPlacement = true,
        )
        val cancelRwButton = if (confirm == 0) 2 else 1
        return runCatching { engine.isMouseButtonDown(cancelRwButton) }.getOrDefault(false)
    }

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
        // The armed build action belongs to the blueprint, so it must not close the game area.
        if ((ui.rwppActiveSpecialAction() != null) ||
            ui.rwppPointerOnMinimap() || ui.rwppPointerDownOverSidebar() ||
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
            invalidPlacements = invalidPlacements,
            canConfirm = mode == BuildingPlanningMode.PLACE_BLUEPRINT && placementSession != null && invalidPlacements == 0,
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
        placementSession = null
        invalidPlacements = 0
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
        suppressOrderUntilButtonRelease = false
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

    private val BLOCKED_BLUEPRINT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 90, 90)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val RECLAIM_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 165, 60)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

}
