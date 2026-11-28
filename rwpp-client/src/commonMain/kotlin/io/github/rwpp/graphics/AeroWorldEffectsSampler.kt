/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.*
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
    private val footprintScratch = RectF()
    private val trails = HashMap<Long, Float>()
    private val liveTrailIds = HashSet<Long>()
    private var lastTrailSampleAt = 0L
    private val productionSampler = AeroProductionSampler()
    private val refresh = WorldVisualRefreshSchedule()
    private val visibleUnits = ArrayList<GameUnit>()
    private val groupClusters = AeroUnitGroupClusters()
    private var nextGroupSampleAt = 0L
    private var sampledGroups: List<AeroUnitGroupVisual> = emptyList()
    private val trackedVisuals = ArrayList<Pair<GameUnit, SelectedVisual>>()
    private val animatedVisuals = ImmutableFrameListCache<Pair<GameUnit, SelectedVisual>>()
    private val lastAnimatedByUnit = java.util.IdentityHashMap<GameUnit, Pair<GameUnit, SelectedVisual>>()
    private var selectedViewSource: List<Pair<GameUnit, SelectedVisual>>? = null
    private var selectedViewValue: List<SelectedVisual>? = null
    private val trackedRanges = AeroRangeTracker<GameUnit>()
    private var sampledSelection: List<GameUnit> = emptyList()
    private var sampledWorld: NativeWorldFrame? = null
    private var sampledGameTime = 0

    private fun resetWorldState() {
        clearDamageTrails()
        productionSampler.clear()
        visibleUnits.clear()
        nextGroupSampleAt = 0L
        sampledGroups = emptyList()
        groupClusters.clear()
        trackedVisuals.clear()
        animatedVisuals.publishStable(emptyList())
        lastAnimatedByUnit.clear()
        selectedViewSource = null
        selectedViewValue = null
        trackedRanges.clear()
        sampledSelection = emptyList()
        refresh.reset()
        sampledWorld = null
        sampledGameTime = 0
        AeroDamageSignals.clear()
        presentation.clear()
    }

    private fun clearDamageTrails() {
        trails.clear()
        liveTrailIds.clear()
        lastTrailSampleAt = 0L
    }

    private fun selectedViewFor(units: List<Pair<GameUnit, SelectedVisual>>): List<SelectedVisual> {
        if (selectedViewSource !== units) {
            selectedViewSource = units
            selectedViewValue = SelectedVisualSnapshot(units)
        }
        return selectedViewValue ?: emptyList()
    }

    /**
     * Native Unit.drawOverlay draws the transport slot grid whenever both display
     * accessors are non-negative. Aero cancels drawOverlay entirely, so the same
     * values have to be sampled here and carried into the replacement bars.
     */
    private fun transportSlots(unit: GameUnit, eligible: Boolean): Pair<Int, Int> {
        if (!eligible || unit.dead || unit.transportedBy != null) return -1 to -1
        return try {
            val used = unit.usedTransportSlotsForDisplay
            val capacity = unit.transportSlotsForDisplay
            if (used >= 0 && capacity > 0) {
                val bounded = capacity.coerceAtMost(MAX_TRANSPORT_SLOTS)
                used.coerceIn(0, bounded) to bounded
            } else -1 to -1
        } catch (_: Throwable) {
            -1 to -1
        }
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
        if (engine == null) {
            resetWorldState()
            return WorldVisuals()
        }
        return synchronized(engine.gameUpdateLock) {
            val world = nativeWorldFrame(engine)
            if (world == null) {
                resetWorldState()
                return@synchronized WorldVisuals()
            }
            if (sampledWorld?.sameWorld(world) != true || engine.gameTimeMillis < sampledGameTime) {
                resetWorldState()
                sampledWorld = world
            }
            sampledGameTime = engine.gameTimeMillis
            sampleActiveFrame(
                world, currentSnapshot, settings, selectedUnits, accent, productionPausedLabel,
                productionWaitingLabel, hoverAllowed, showAllReplayOrders
            )
        }
    }

    private fun sampleActiveFrame(
        world: NativeWorldFrame,
        currentSnapshot: WorldVisuals,
        settings: Settings,
        selectedUnits: List<GameUnit>,
        accent: Color,
        productionPausedLabel: String,
        productionWaitingLabel: String,
        hoverAllowed: Boolean,
        showAllReplayOrders: Boolean,
    ): WorldVisuals {
        val engine = world.engine
        val viewer = world.viewer
        AeroDamageSignals.enabled = settings.aeroDamageNumbers
        if (!settings.aeroDamageNumbers) AeroDamageSignals.clear()
        if (settings.aeroProductionLabels) productionSampler.beginFrame() else productionSampler.clear()
        val explicitRanges = rangeSettings.hasExplicitRanges()
        val rangesEnabled =
            explicitRanges || settings.showBuildingAttackRange || settings.showAttackRangeUnit != "Never"
        val replay = engine.replayEngine.isPlayingReplay
        val observing = isAeroLiveObserver(engine)
        val replaying = replay || observing
        val showAllOrders = replaying && showAllReplayOrders
        val showUnitGroups = settings.exclusiveUnitGroups && settings.aeroWorldUnitGroups && !replaying
        val now = System.nanoTime()
        if (!showUnitGroups) {
            nextGroupSampleAt = 0L
            sampledGroups = emptyList()
        } else if (nextGroupSampleAt == 0L || now >= nextGroupSampleAt) {
            val next = sampleUnitGroups(world)
            if (next != sampledGroups) sampledGroups = next
            nextGroupSampleAt = now + AERO_GROUP_REFRESH_NANOS
        }
        val aeroBars = settings.aeroSelection
        val animateDamage = aeroBars && settings.aeroDamageAnimation
        if (!animateDamage && lastTrailSampleAt != 0L) clearDamageTrails()
        if (!aeroBars && !settings.showCollisionBoxes && !settings.showUnitTargetLine && !settings.aeroHoverEffects && !settings.aeroUnitGlowHighlight &&
            !settings.aeroOrderEffects && !rangesEnabled && !settings.aeroDamageNumbers &&
            !settings.aeroRallyPointEffects && !settings.advancedRallyPoints && !settings.aeroProductionLabels &&
            !showAllOrders
        ) {
            trackedVisuals.clear()
            trackedRanges.clear()
            animatedVisuals.publishStable(emptyList())
            lastAnimatedByUnit.clear()
            selectedViewFor(emptyList())
            val groupSnapshot = if (sampledGroups.isEmpty()) {
                if (currentSnapshot.hasDrawableEffects) WorldVisuals() else currentSnapshot
            } else WorldVisuals(
                unitGroups = sampledGroups, camera = Offset(engine.cameraRenderX, engine.cameraRenderY),
                zoom = engine.zoomScale, width = engine.screenWidth, height = engine.screenHeight,
            )
            presentation.publishFrameSnapshot(groupSnapshot, emptyList(), emptyList())
            return groupSnapshot
        }

        val ui = engine.interfaceEngine
        val selectedUnitCount = ui.getSelectedUnitCount()
        val bulkSelection = aeroBars && selectedUnitCount >= AERO_BULK_SELECTION_COUNT
        val startingTrail = animateDamage && lastTrailSampleAt == 0L
        val trailElapsed = if (animateDamage && !startingTrail) (now - lastTrailSampleAt) / 1_000_000_000f else 0f
        if (animateDamage) lastTrailSampleAt = now
        fun sampleTrail(unit: GameUnit, hp: Float): Float {
            if (!animateDamage) return hp
            return damageTrail(trails[unit.id] ?: hp, hp, trailElapsed).also { trails[unit.id] = it }
        }
        val selectionChanged = sampledSelection !== selectedUnits
        if (!refresh.due(now, force = selectionChanged || startingTrail)) {
            // Follow native unit positions each display frame; do not rescan the world or
            // resample range eligibility/radii just to advance the passive overlay animation.
            val animatedTracking = animatedVisuals.update(trackedVisuals) { entry ->
                val unit = entry.first
                val visual = lastAnimatedByUnit[unit]?.second ?: entry.second
                if (unit.dead || unit.transportedBy != null || !nativeUnitVisibleInFrame(world, unit)) {
                    return@update null
                }
                val hp = unitHealthRatio(unit.hp, unit.maxHp)
                val trail = sampleTrail(unit, hp)
                val center = Offset(unit.x, unit.y - unit.height)
                val shield = unitHealthRatio(unit.shield, unit.maxShield)
                val secondary = if (aeroBars) aeroNonShieldSecondary(unit) else -1f
                val recharging = secondary >= 0f && unit.isRechargingEnergy
                val selected = aeroBars && !bulkSelection && unit.selected
                val radius = unit.radius
                val scaledDown = unit.drawnScaledDown
                val (transportUsed, transportCapacity) = transportSlots(
                    unit, aeroBars && visual.transportCapacity > 0
                )
                val next = if (visual.center == center && visual.hp == hp && visual.trail == trail &&
                    visual.shield == shield && visual.selected == selected && visual.barRadius == radius &&
                    visual.barScaledDown == scaledDown && visual.transportUsed == transportUsed &&
                    visual.transportCapacity == transportCapacity && visual.secondary == secondary &&
                    visual.secondaryRecharging == recharging
                ) {
                    lastAnimatedByUnit[unit] ?: (unit to visual)
                } else {
                    unit to visual.copy(
                        center = center, hp = hp, trail = trail, shield = shield,
                        selected = selected, barRadius = radius, barScaledDown = scaledDown,
                        transportUsed = transportUsed, transportCapacity = transportCapacity,
                        secondary = secondary, secondaryRecharging = recharging
                    )
                }
                lastAnimatedByUnit[unit] = next
                next
            }
            val selectedView = selectedViewFor(animatedTracking)
            val nextSnapshot = currentSnapshot.copy(
                selected = selectedView,
                unitGroups = sampledGroups,
                bulkSelectionBounds = currentSnapshot.bulkSelectionBounds.takeIf { bulkSelection },
                ranges = trackedRanges.frame { unit ->
                    if (unit.dead || unit.transportedBy != null || !nativeUnitVisibleInFrame(
                            world,
                            unit
                        )
                    ) null
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
            if (!unit.dead && unit.transportedBy == null && nativeUnitVisibleInFrame(world, unit)) {
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
        val orders = ArrayList<AeroOrder>()
        val circles = ArrayList<AeroRangeCircle>()
        val production = ArrayList<AeroProductionVisual>()
        val collisionBounds = if (settings.showCollisionBoxes) ArrayList<AeroCollisionVisual>() else null
        val targetLines = if (settings.showUnitTargetLine) ArrayList<Pair<Offset, Offset>>() else null
        val left = engine.cameraRenderX
        val top = engine.cameraRenderY
        val right = left + engine.screenWidth / engine.zoomScale
        val bottom = top + engine.screenHeight / engine.zoomScale
        var selectionLeft = Float.POSITIVE_INFINITY
        var selectionTop = Float.POSITIVE_INFINITY
        var selectionRight = Float.NEGATIVE_INFINITY
        var selectionBottom = Float.NEGATIVE_INFINITY
        liveTrailIds.clear()
        allVisible.forEach { unit ->
            val inView = unit.x + unit.radius >= left && unit.y + unit.radius >= top &&
                    unit.x - unit.radius <= right && unit.y - unit.radius <= bottom
            if (inView && collisionBounds != null && unit.collisionEnabled) {
                val footprint = if (unit.isBuilding) {
                    unit.getMinimapRect(world.map, footprintScratch)
                    Rect(footprintScratch.left, footprintScratch.top, footprintScratch.right, footprintScratch.bottom)
                } else null
                collisionBounds += AeroCollisionVisual(Offset(unit.x, unit.y), unit.radius, footprint)
            }
            if (inView && targetLines != null && unit is OrderableUnit && unit.maxAttackRange > 70f &&
                (!engine.networkEngine.networked || unit.team.isSameTeam(viewer))
            ) {
                val target = unit.attackTarget
                if (target != null && !target.dead && nativeUnitVisibleInFrame(world, target)) {
                    targetLines += Offset(unit.x, unit.y) to Offset(target.x, target.y)
                }
            }
            val bulkMember = bulkSelection && unit.selected
            if (bulkMember && unit.selectionRadius.isFinite() && unit.height.isFinite()) {
                val radius = unit.selectionRadius.coerceIn(8f, 96f)
                val centerY = unit.y - unit.height
                selectionLeft = minOf(selectionLeft, unit.x - radius)
                selectionRight = maxOf(selectionRight, unit.x + radius)
                selectionTop = minOf(selectionTop, centerY - radius)
                selectionBottom = maxOf(selectionBottom, centerY + radius)
            }
            if (inView && (aeroBars || unit === hovered || settings.aeroUnitGlowHighlight && unit.selected)) {
                val hp = unitHealthRatio(unit.hp, unit.maxHp)
                val trail = if (animateDamage && inView) {
                    // A healthy unselected unit may get a visible bar only after taking damage.
                    liveTrailIds.add(unit.id)
                    sampleTrail(unit, hp)
                } else hp
                val construction = unit.constructionProgress
                val secondary = if (inView && aeroBars) aeroNonShieldSecondary(unit) else -1f
                val isHovered = unit === hovered
                val health = nativeUnitHealthBarAllowed(unit) &&
                        aeroHealthVisible(unit.selected, isHovered, engine.settings.showHp, hp, selectedUnitCount)
                val individuallySelected = unit.selected && !bulkMember
                val glowHighlight = settings.aeroUnitGlowHighlight && (individuallySelected || isHovered)
                val shieldVisible =
                    aeroShieldVisible(aeroBars, unit.selected, isHovered, unit.shield, unit.maxShield)
                val enemy = viewer != null && unit.team.isEnemy(viewer)
                val selectionColor = aeroSelectionHighlightColor(
                    ownTeam = unit.team === engine.playerTeam,
                    enemy = enemy,
                    observing = observing,
                    replay = replay,
                    accent = accent,
                )
                val (transportUsed, transportCapacity) = transportSlots(
                    unit, aeroBars && inView &&
                            (unit.selected || isHovered || engine.settings.showHp)
                )
                if (inView && (aeroBars &&
                            (individuallySelected || health || shieldVisible || construction >= 0f || secondary >= 0f ||
                                    transportCapacity > 0) || isHovered || glowHighlight)
                ) {
                    val visual = SelectedVisual(
                        Offset(unit.x, unit.y - unit.height),
                        unit.selectionRadius.coerceAtLeast(8f),
                        hp,
                        unitHealthRatio(unit.shield, unit.maxShield),
                        if (enemy) AeroEnemyHighlight else accent,
                        selected = aeroBars && individuallySelected,
                        hovered = isHovered,
                        healthVisible = aeroBars && health,
                        construction = if (aeroBars) construction else -1f,
                        secondary = if (aeroBars) secondary else -1f,
                        secondaryRecharging = secondary >= 0f && unit.isRechargingEnergy,
                        shieldVisible = shieldVisible,
                        glowHighlight = glowHighlight,
                        selectionColor = selectionColor,
                        barRadius = unit.radius,
                        barScaledDown = unit.drawnScaledDown,
                        barMini = unit.getAttachmentSlot()?.showMiniHp == true,
                        transportUsed = transportUsed,
                        transportCapacity = transportCapacity,
                        trail = trail,
                    )
                    trackedVisuals += unit to visual
                }
            }
            if (settings.aeroProductionLabels && inView && unit.isBuilding &&
                (unit.team === viewer || settings.aeroAlliedProductionLabels && unit.team.isSameTeam(
                    viewer
                ))
            ) {
                productionSampler.sample(unit, engine.gameTimeMillis.toLong(), engine.isGamePaused(false))
                    ?.let(production::add)
            }
            if (unit is OrderableUnit) {
                val forcedRange = explicitRanges && rangeSettings.isAttackRangeShown(unit)
                if (rangesEnabled && (forcedRange || !settings.aeroEnemyRangesOnly || unit.team.isEnemy(
                        viewer
                    )) &&
                    aeroAttackRangeAllowed(
                        unit.maxAttackRange, unit.movementType, unit.isBuilding,
                        settings.showAttackRangeUnit, settings.showBuildingAttackRange, forcedRange
                    )
                ) {
                    val radius = unit.maxAttackRange
                    if (unit.x + radius >= left && unit.x - radius <= right &&
                        unit.y + radius >= top && unit.y - radius <= bottom
                    ) {
                        val ownership = aeroRangeOwnership(
                            unit.team.teamId, unit.team.allyTeam, Color(unit.team.color), settings.aeroRangeUseTeamColors
                        )
                        val circle = AeroRangeCircle(Offset(unit.x, unit.y), radius, ownership.group, ownership.color)
                        if (circle.hasUsableBounds()) {
                            circles += circle
                            trackedRanges.add(unit, circle)
                        }
                    }
                }
                if (settings.aeroOrderEffects || (settings.aeroRallyPointEffects || settings.advancedRallyPoints) && unit.selected || showAllOrders) {
                    val sameReplayTeam = unit.team === viewer
                    val sharedReplayControl =
                        unit.team != null && viewer != null && unit.team.canControlTeam(
                            viewer
                        )
                    val mayShowOrders = aeroReplayOrderAccess(
                        replaying, showAllOrders, sameReplayTeam,
                        sharedReplayControl, ui.canPlayerControlUnit(unit)
                    )
                    val route = if (mayShowOrders && settings.advancedRallyPoints) AdvancedRally.displayedRoute(unit) else null
                    if (route != null) {
                        var from = Offset(unit.x, unit.y)
                        for ((index, point) in route.withIndex()) {
                            orders += AeroOrder(from, point, false, kind = AeroOrderKind.RALLY, step = index, team = unit.team.teamId)
                            from = point
                        }
                    } else if (((settings.aeroRallyPointEffects && unit.selected) || showAllOrders) && mayShowOrders) {
                        NativeFactoryOverlays.rallyPoint(unit)?.let { point ->
                            val needsFogGate = showAllOrders && !sameReplayTeam && !sharedReplayControl
                            val map = engine.map
                            val destinationVisible = aeroReplayWaypointDestinationVisible(
                                map?.fogOfWarEnabled ?: false, viewer.fogGrid,
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
                            val targetVisible =
                                target == null || (!target.dead && nativeUnitVisibleInFrame(
                                    world,
                                    target
                                ))
                            val needsFogGate = showAllOrders && !sameReplayTeam && !sharedReplayControl
                            val map = engine.map
                            val destinationVisible = aeroReplayWaypointDestinationVisible(
                                map?.fogOfWarEnabled ?: false, viewer.fogGrid,
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

        // The native green grid owns placement geometry. Tile snapping, footprint expansion
        // and resource-pool adjustment have already been applied when it is drawn, so the
        // Aero build effect must use those bounds instead of the raw pointer position.
        val action = ui.activeSpecialAction
        if (settings.aeroOrderEffects && settings.aeroBuildEffects && action?.actionType == ActionType.placeBuilding) {
            val source = ui.specialActionSourceUnit ?: selectedUnits.firstOrNull()
            val type = action.unitType
            if (source != null && type != null && ui.canPlayerControlUnit(source)) {
                val radius = GameUnit.getPlaceholderOrDefault(type).radius.coerceAtLeast(8f)
                val cells = NativePlacementGrid.snapshot()
                if (cells.isEmpty()) {
                    val to = Offset(
                        ui.placingPointerX / engine.zoomScale + engine.cameraRenderX,
                        ui.placingPointerY / engine.zoomScale + engine.cameraRenderY
                    )
                    orders += AeroOrder(
                        Offset(source.x, source.y), to, false, kind = AeroOrderKind.BUILD,
                        team = source.team.teamId, preview = true, radius = radius
                    )
                } else {
                    // Match the queued-build chain: the first line starts at the builder
                    // and every later square continues from the previous one, in the order
                    // the native placement drag reports them.
                    placementChain(Offset(source.x, source.y), cells).forEachIndexed { index, link ->
                        val cell = cells[index]
                        orders += AeroOrder(
                            link.first, link.second, false, kind = AeroOrderKind.BUILD,
                            team = source.team.teamId, preview = true, radius = radius,
                            step = index,
                            grid = AeroPlacementGrid(
                                cell.left, cell.top, cell.right, cell.bottom, cell.stepX, cell.stepY
                            )
                        )
                    }
                }
                if (!settings.aeroEnemyRangesOnly || source.team.isEnemy(viewer)) {
                    val circle = AeroRangeCircle(
                        Offset(source.x, source.y), source.getBuildRange(type),
                        source.team.teamId, Color(0xff72d8f5), "build"
                    )
                    if (circle.hasUsableBounds()) {
                        circles += circle
                        trackedRanges.add(source, circle)
                    }
                }
            }
        }

        if (animateDamage) trails.keys.retainAll(liveTrailIds)
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
            productionWaitingLabel,
            collisionBounds = collisionBounds.orEmpty(),
            targetLines = targetLines.orEmpty(),
            unitGroups = sampledGroups,
            bulkSelectionBounds = if (selectionLeft < selectionRight && selectionTop < selectionBottom) {
                Rect(selectionLeft, selectionTop, selectionRight, selectionBottom).inflate(5f)
            } else null,
        )
        presentation.publishFrameSnapshot(snapshot, immutableTracking, trackedRanges.snapshot())
        return snapshot
    }

    private fun sampleUnitGroups(world: NativeWorldFrame): List<AeroUnitGroupVisual> {
        val engine = world.engine
        val zoom = engine.zoomScale
        if (!zoom.isFinite() || zoom <= 0f) return emptyList()

        val area = Rect(
            engine.cameraRenderX,
            engine.cameraRenderY,
            engine.cameraRenderX + engine.screenWidth / zoom,
            engine.cameraRenderY + engine.screenHeight / zoom
        ).inflate(AERO_GROUP_SAMPLE_MARGIN)
        groupClusters.clear()
        NativeHudGroups.forEachMember(engine) { number, unit ->
            if (unit !is OrderableUnit || unit.dead || unit.transportedBy != null) return@forEachMember
            val center = Offset(unit.x, unit.y - unit.height)
            if (center !in area || !nativeUnitVisibleInFrame(world, unit) ||
                !engine.interfaceEngine.canPlayerControlUnit(unit)
            ) return@forEachMember
            val radius = if (unit.radius.isFinite()) unit.radius.coerceIn(6f, 26f) else 6f
            groupClusters.add(number, center.x, center.y, radius)
        }
        return groupClusters.build()
    }
}

/** A transport with an unreasonable slot count must not stall the HUD frame. */
private const val MAX_TRANSPORT_SLOTS = 64
