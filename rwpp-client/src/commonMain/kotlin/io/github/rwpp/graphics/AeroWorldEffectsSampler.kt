/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.*
import io.github.rwpp.game.replay.aeroPresentationTeam
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import kotlin.math.min
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Read-only view over a frame-owned unit snapshot; it avoids allocating a second visual list. */
private class SelectedVisualSnapshot(
    private val units: List<Pair<GameUnit, SelectedVisual>>
) : AbstractList<SelectedVisual>() {
    override val size: Int get() = units.size
    override fun get(index: Int): SelectedVisual = units[index].second
}

/**
 * Samples native game state on the caller's game-update thread. All collections handed to
 * [AeroWorldPresentation] are immutable snapshots; scratch storage stays private to this sampler.
 */
internal class AeroWorldEffectsSampler(
    private val rangeSettings: EntityRangeUnitComp,
    private val presentation: AeroWorldPresentation,
) {
    private val trails = HashMap<Int, Float>()
    private val productionSampler = AeroProductionSampler()
    private val refresh = WorldVisualRefreshSchedule()
    private val visibleUnits = ArrayList<GameUnit>()
    private val trackedVisuals = ArrayList<Pair<GameUnit, SelectedVisual>>()
    private val animatedVisuals = ImmutableFrameListCache<Pair<GameUnit, SelectedVisual>>()
    private val lastAnimatedByUnit = java.util.IdentityHashMap<GameUnit, Pair<GameUnit, SelectedVisual>>()
    private var selectedViewSource: List<Pair<GameUnit, SelectedVisual>>? = null
    private var selectedViewValue: List<SelectedVisual>? = null
    private val liveTrailIds = HashSet<Int>()
    private val trackedRanges = AeroRangeTracker<GameUnit>()
    private var sampledSelection: List<GameUnit> = emptyList()
    private var lastTime = 0L

    private fun selectedViewFor(units: List<Pair<GameUnit, SelectedVisual>>): List<SelectedVisual> {
        if (selectedViewSource !== units) {
            selectedViewSource = units
            selectedViewValue = SelectedVisualSnapshot(units)
        }
        return selectedViewValue ?: emptyList()
    }

    fun sampleFrame(
        currentSnapshot: WorldVisuals,
        settings: Settings,
        selectedUnits: List<GameUnit>,
        accent: Color,
        productionPausedLabel: String,
        productionWaitingLabel: String,
        hoverAllowed: Boolean,
        showAllReplayOrders: Boolean,
    ): WorldVisuals {
        val engine = GameEngine.getInstance()
        AeroDamageSignals.enabled = settings.aeroDamageNumbers
        if (!settings.aeroDamageNumbers) AeroDamageSignals.clear()
        if (settings.aeroProductionLabels) productionSampler.beginFrame() else productionSampler.clear()
        val explicitRanges = rangeSettings.hasExplicitRanges()
        val rangesEnabled =
            explicitRanges || settings.showBuildingAttackRange || settings.showAttackRangeUnit != "Never"
        val replayOrdersEnabled =
            (engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)) && showAllReplayOrders
        if (!settings.aeroSelection && !settings.aeroHoverEffects && !settings.aeroUnitGlowHighlight &&
            !settings.aeroOrderEffects && !rangesEnabled && !settings.aeroDamageNumbers &&
            !settings.aeroRallyPointEffects && !settings.aeroProductionLabels && !replayOrdersEnabled
        ) {
            val emptySnapshot = if (currentSnapshot.selected.isNotEmpty() || currentSnapshot.orders.isNotEmpty() ||
                currentSnapshot.ranges.isNotEmpty() || currentSnapshot.damageNumbers.isNotEmpty() ||
                currentSnapshot.production.isNotEmpty()
            ) WorldVisuals() else currentSnapshot
            trails.clear()
            trackedVisuals.clear()
            trackedRanges.clear()
            animatedVisuals.publishStable(emptyList())
            lastAnimatedByUnit.clear()
            selectedViewFor(emptyList())
            presentation.clear()
            lastTime = 0L
            return emptySnapshot
        }

        val ui = engine.interfaceEngine
        val selectedUnitCount = ui.getSelectedUnitCount()
        val now = System.nanoTime()
        val dt = if (lastTime == 0L) 0f else (now - lastTime) / 1_000_000_000f
        lastTime = now
        val selectionChanged = sampledSelection !== selectedUnits
        if (!refresh.due(now, force = selectionChanged)) {
            // Follow native unit positions each display frame; do not rescan the world or
            // resample range eligibility/radii just to advance the passive overlay animation.
            val animatedTracking = animatedVisuals.update(trackedVisuals) { entry ->
                val unit = entry.first
                val visual = lastAnimatedByUnit[unit]?.second ?: entry.second
                if (unit.dead || unit.transportedBy != null || !unit.isVisibleToTeam(aeroPresentationTeam(engine))) {
                    return@update null
                }
                val hp = unitHealthRatio(unit.hp, unit.maxHp)
                val identity = System.identityHashCode(unit)
                val trail = if (settings.aeroDamageAnimation) damageTrail(trails[identity] ?: hp, hp, dt) else hp
                trails[identity] = trail
                val center = Offset(unit.x, unit.y - unit.height)
                val shield = unitHealthRatio(unit.shield, unit.maxShield)
                val selected = settings.aeroSelection && unit.selected
                val radius = unit.radius
                val scaledDown = unit.drawnScaledDown
                val next = if (visual.center == center && visual.hp == hp && visual.trail == trail &&
                    visual.shield == shield && visual.selected == selected && visual.barRadius == radius &&
                    visual.barScaledDown == scaledDown
                ) {
                    lastAnimatedByUnit[unit] ?: (unit to visual)
                } else {
                    unit to visual.copy(
                        center = center, hp = hp, trail = trail, shield = shield,
                        selected = selected, barRadius = radius, barScaledDown = scaledDown
                    )
                }
                lastAnimatedByUnit[unit] = next
                next
            }
            val selectedView = selectedViewFor(animatedTracking)
            val nextSnapshot = currentSnapshot.copy(
                selected = selectedView,
                ranges = trackedRanges.frame { unit ->
                    if (unit.dead || unit.transportedBy != null || !unit.isVisibleToTeam(aeroPresentationTeam(engine))) null
                    else Offset(unit.x, unit.y)
                },
                damageNumbers = AeroDamageSignals.frame(engine.gameTimeMillis.toLong()),
                camera = Offset(engine.cameraRenderX, engine.cameraRenderY),
                zoom = engine.zoomScale, width = engine.screenWidth.coerceAtLeast(1f),
                height = engine.screenHeight.coerceAtLeast(1f), phase = (now % 2_000_000_000L) / 2_000_000_000f
            )
            presentation.publishFrameSnapshot(nextSnapshot, animatedTracking, trackedRanges.snapshot())
            return nextSnapshot
        }

        sampledSelection = selectedUnits
        trackedVisuals.clear()
        trackedRanges.clear()
        visibleUnits.clear()
        for (entry in GameUnit.unitList) {
            val unit = entry as? GameUnit ?: continue
            if (!unit.dead && unit.transportedBy == null && unit.isVisibleToTeam(aeroPresentationTeam(engine))) {
                visibleUnits.add(unit)
            }
        }
        val allVisible = visibleUnits
        val mouse = Offset(
            ui.pointerX / engine.zoomScale + engine.cameraRenderX,
            ui.pointerY / engine.zoomScale + engine.cameraRenderY
        )
        var hovered: GameUnit? = null
        if (settings.aeroHoverEffects && hoverAllowed) {
            var nearest = Float.POSITIVE_INFINITY
            for (unit in allVisible) {
                val distance = (Offset(unit.x, unit.y - unit.height) - mouse).getDistanceSquared()
                val radius = unit.radius + 4f / engine.zoomScale
                if (distance <= radius * radius && distance < nearest) {
                    hovered = unit
                    nearest = distance
                }
            }
        }
        val live = liveTrailIds
        live.clear()
        val orders = ArrayList<AeroOrder>()
        val circles = ArrayList<AeroRangeCircle>()
        val production = ArrayList<AeroProductionVisual>()
        val replaying = engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)
        val showAllOrders = replaying && showAllReplayOrders
        allVisible.forEach { unit ->
            val inView = unit.x + unit.radius >= engine.cameraRenderX && unit.y + unit.radius >= engine.cameraRenderY &&
                    unit.x - unit.radius <= engine.cameraRenderX + engine.screenWidth / engine.zoomScale &&
                    unit.y - unit.radius <= engine.cameraRenderY + engine.screenHeight / engine.zoomScale
            val hp = unitHealthRatio(unit.hp, unit.maxHp)
            val construction = unit.constructionProgress
            val secondary = if (inView && settings.aeroSelection) aeroNonShieldSecondary(unit) else -1f
            val isHovered = unit === hovered
            val health = nativeUnitHealthBarAllowed(unit) &&
                    aeroHealthVisible(unit.selected, isHovered, engine.settings.showHp, hp, selectedUnitCount)
            val glowHighlight = settings.aeroUnitGlowHighlight && (unit.selected || isHovered)
            val shieldVisible =
                aeroShieldVisible(settings.aeroSelection, unit.selected, isHovered, unit.shield, unit.maxShield)
            val ownerColor = Color(unit.team.color)
            if (inView && (settings.aeroSelection &&
                        (unit.selected || health || shieldVisible || construction >= 0f || secondary >= 0f) || isHovered || glowHighlight)
            ) {
                val identity = System.identityHashCode(unit)
                live += identity
                val trail = if (settings.aeroDamageAnimation) damageTrail(trails[identity] ?: hp, hp, dt) else hp
                trails[identity] = trail
                val visual = SelectedVisual(
                    Offset(unit.x, unit.y - unit.height),
                    unit.selectionRadius.coerceAtLeast(8f),
                    hp,
                    trail,
                    unitHealthRatio(unit.shield, unit.maxShield),
                    if (unit.team.isEnemy(aeroPresentationTeam(engine))) Color(0xffff6565) else accent,
                    selected = settings.aeroSelection && unit.selected,
                    hovered = isHovered,
                    healthVisible = settings.aeroSelection && health,
                    construction = if (settings.aeroSelection) construction else -1f,
                    secondary = if (settings.aeroSelection) secondary else -1f,
                    shieldVisible = shieldVisible,
                    glowHighlight = glowHighlight,
                    barRadius = unit.radius,
                    barScaledDown = unit.drawnScaledDown,
                    barMini = unit.getAttachmentSlot()?.showMiniHp == true
                )
                trackedVisuals += unit to visual
            }
            if (settings.aeroProductionLabels && inView && unit.isBuilding &&
                (unit.team === aeroPresentationTeam(engine) || settings.aeroAlliedProductionLabels && unit.team.isSameTeam(
                    aeroPresentationTeam(engine)
                ))
            ) {
                productionSampler.sample(unit, engine.gameTimeMillis.toLong(), engine.isGamePaused(false))
                    ?.let(production::add)
            }
            if (unit is OrderableUnit) {
                val forcedRange = rangeSettings.isAttackRangeShown(unit)
                if (rangesEnabled && (forcedRange || !settings.aeroEnemyRangesOnly || unit.team.isEnemy(
                        aeroPresentationTeam(engine)
                    )) &&
                    aeroAttackRangeAllowed(
                        unit.maxAttackRange, unit.movementType, unit.isBuilding,
                        settings.showAttackRangeUnit, settings.showBuildingAttackRange, forcedRange
                    )
                ) {
                    val radius = unit.maxAttackRange
                    if (unit.x + radius >= engine.cameraRenderX && unit.x - radius <= engine.cameraRenderX + engine.screenWidth / engine.zoomScale &&
                        unit.y + radius >= engine.cameraRenderY && unit.y - radius <= engine.cameraRenderY + engine.screenHeight / engine.zoomScale
                    ) {
                        val ownership = aeroRangeOwnership(
                            unit.team.teamId, unit.team.allyTeam, ownerColor, settings.aeroRangeUseTeamColors
                        )
                        val circle = AeroRangeCircle(Offset(unit.x, unit.y), radius, ownership.group, ownership.color)
                        circles += circle
                        trackedRanges.add(unit, circle)
                    }
                }
                if (settings.aeroOrderEffects || settings.aeroRallyPointEffects && unit.selected || showAllOrders) {
                    val sameReplayTeam = unit.team === aeroPresentationTeam(engine)
                    val sharedReplayControl =
                        unit.team != null && aeroPresentationTeam(engine) != null && unit.team.canControlTeam(
                            aeroPresentationTeam(engine)
                        )
                    val mayShowOrders = aeroReplayOrderAccess(
                        replaying, showAllOrders, sameReplayTeam,
                        sharedReplayControl, ui.canPlayerControlUnit(unit)
                    )
                    if (((settings.aeroRallyPointEffects && unit.selected) || showAllOrders) && mayShowOrders) {
                        NativeFactoryOverlays.rallyPoint(unit)?.let { point ->
                            val needsFogGate = showAllOrders && !sameReplayTeam && !sharedReplayControl
                            val map = engine.map
                            val destinationVisible = aeroReplayWaypointDestinationVisible(
                                map?.fogOfWarEnabled ?: false, aeroPresentationTeam(engine)?.fogGrid,
                                map?.tileWidth ?: 0, map?.tileHeight ?: 0,
                                map?.mapWidthInTiles ?: 0, map?.mapHeightInTiles ?: 0,
                                point.x, point.y, targetUnitVisible = true, requireCurrentVision = needsFogGate
                            )
                            if (destinationVisible) {
                                orders += AeroOrder(
                                    Offset(unit.x, unit.y), point, false,
                                    kind = AeroOrderKind.RALLY, team = unit.team.teamId
                                )
                            }
                        }
                    }
                    if ((settings.aeroOrderEffects || showAllOrders) && mayShowOrders) {
                        var from = Offset(unit.x, unit.y)
                        val count = if (showAllOrders || settings.aeroWaypointEffects) unit.waypointCount
                        else min(1, unit.waypointCount)
                        for (index in 0 until count) {
                            val waypoint = unit.getWaypoint(index) ?: continue
                            val target = waypoint.targetUnit
                            val targetVisible = target == null || target.isVisibleToTeam(aeroPresentationTeam(engine))
                            val needsFogGate = showAllOrders && !sameReplayTeam && !sharedReplayControl
                            val map = engine.map
                            val destinationVisible = aeroReplayWaypointDestinationVisible(
                                map?.fogOfWarEnabled ?: false, aeroPresentationTeam(engine)?.fogGrid,
                                map?.tileWidth ?: 0, map?.tileHeight ?: 0,
                                map?.mapWidthInTiles ?: 0, map?.mapHeightInTiles ?: 0,
                                waypoint.targetX, waypoint.targetY, targetVisible, needsFogGate
                            )
                            if (!destinationVisible) break
                            val kind = aeroOrderKind(waypoint.type.name) ?: continue
                            val to = Offset(waypoint.targetX, waypoint.targetY)
                            val allowed = showAllOrders ||
                                    ((kind != AeroOrderKind.GUARD || settings.aeroGuardEffects) &&
                                            (kind !in listOf(
                                                AeroOrderKind.BUILD,
                                                AeroOrderKind.REPAIR,
                                                AeroOrderKind.RECLAIM
                                            ) ||
                                                    settings.aeroBuildEffects))
                            if (allowed) {
                                val radius = target?.radius ?: 16f
                                orders += AeroOrder(
                                    from, to, kind == AeroOrderKind.ATTACK,
                                    kind = kind, step = index, team = unit.team.teamId, radius = radius,
                                    progress = target?.constructionProgress ?: -1f
                                )
                            }
                            from = to
                        }
                    }
                }
            }
        }

        // Placement has no queued waypoint yet. Highlight the native preview, using its own pointer coordinates.
        val action = ui.activeSpecialAction
        if (settings.aeroOrderEffects && settings.aeroBuildEffects && action?.actionType == ActionType.placeBuilding) {
            val source = ui.specialActionSourceUnit ?: selectedUnits.firstOrNull()
            val type = action.unitType
            if (source != null && type != null && ui.canPlayerControlUnit(source)) {
                val to = Offset(
                    ui.placingPointerX / engine.zoomScale + engine.cameraRenderX,
                    ui.placingPointerY / engine.zoomScale + engine.cameraRenderY
                )
                val radius = GameUnit.getPlaceholderOrDefault(type).radius.coerceAtLeast(8f)
                orders += AeroOrder(
                    Offset(source.x, source.y), to, false, kind = AeroOrderKind.BUILD,
                    team = source.team.teamId, preview = true, radius = radius
                )
                if (!settings.aeroEnemyRangesOnly || source.team.isEnemy(aeroPresentationTeam(engine))) {
                    val circle = AeroRangeCircle(
                        Offset(source.x, source.y), source.getBuildRange(type),
                        source.team.teamId, Color(0xff72d8f5), "build"
                    )
                    circles += circle
                    trackedRanges.add(source, circle)
                }
            }
        }

        trails.keys.retainAll(live)
        productionSampler.endFrame()
        val immutableTracking = trackedVisuals.toList()
        animatedVisuals.publishStable(immutableTracking)
        lastAnimatedByUnit.clear()
        immutableTracking.forEach { lastAnimatedByUnit[it.first] = it }
        val selectedView = selectedViewFor(immutableTracking)
        val snapshot = WorldVisuals(
            selectedView,
            aeroOrderGroups(orders, settings.aeroGroupOrders),
            Offset(engine.cameraRenderX, engine.cameraRenderY),
            engine.zoomScale,
            engine.screenWidth.coerceAtLeast(1f),
            engine.screenHeight.coerceAtLeast(1f),
            (now % 2_000_000_000L) / 2_000_000_000f,
            buildAeroRangeGroups(circles),
            AeroDamageSignals.frame(engine.gameTimeMillis.toLong()),
            settings.aeroRangeEffects,
            production,
            productionPausedLabel,
            productionWaitingLabel
        )
        presentation.publishFrameSnapshot(snapshot, immutableTracking, trackedRanges.snapshot())
        return snapshot
    }
}
