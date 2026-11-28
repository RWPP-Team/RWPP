/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.IndirectedAction
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.actions.SpecialActionCategory
import com.corrodinggames.rts.game.units.actions.UnitInfoBox
import com.corrodinggames.rts.game.units.actions.UnitTypeInfo
import com.corrodinggames.rts.game.units.custom.price.CustomPrice
import com.corrodinggames.rts.game.units.custom.resources.CustomResource
import com.corrodinggames.rts.game.units.custom.resources.StoredResource
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputControllerButton
import com.corrodinggames.rts.gameFramework.gui.BattleInterface
import io.github.rwml.api.Platform
import io.github.rwml.api.Rwml
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hud.*
import io.github.rwpp.game.hotkeys.NativeGameHotkeys
import io.github.rwpp.game.sandbox.sandboxNativeEditorActionsAllowed
import io.github.rwpp.game.sandbox.sandboxUnitDataUiAllowed
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.group_30
import io.github.rwpp.graphics.ActionImages
import io.github.rwpp.logic.hud.GameUiPauseState
import io.github.rwpp.logic.hud.NativeHudVisibility
import io.github.rwpp.widget.v2.AeroIconButton
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

internal data class CommandButton(
    val label: String, val heightScale: Float = 1f, val columns: Int = -1,
    val enabled: Boolean = true, val progress: Float = 0f, val queued: Int = 0,
    val cooldown: Float = 0f,
    val image: ImageBitmap? = null, val info: () -> CommandInfo = { CommandInfo(label, "") },
    val activate: (Boolean) -> Unit = {}, val showInfo: Boolean = true,
    val glyph: HudActionGlyph? = null,
    val canAdd: Boolean = enabled,
    val clickSound: io.github.rwpp.api.game.audio.GameSound? = io.github.rwpp.sound.Sounds.select,
    val identity: String = label,
    val dimmed: Boolean = !enabled,
    val hotkey: String = "",
    /** Native action-bar appearance inputs; see BattleInterface.renderActionBar. */
    val category: SpecialActionCategory = SpecialActionCategory.none,
    val techLevel: Int = 1,
    /** BattleInterface.isSpecialActionUsableBySelection: affordable for at least one selected owner. */
    val usable: Boolean = false,
    /** BattleInterface.isSpecialActionUsable: the pulsing hint, not an affordability check. */
    val flash: Boolean = false,
    val active: Boolean = false,
    val demoBlocked: Boolean = false,
    val pendingProduction: Int = 0,
    val opensBuildingPlacement: Boolean = false,
    val opensTouchQueue: Boolean = false,
    /** Minimum economy to keep every eligible factory producing this unit; null hides it. */
    val economy: FactoryEconomy? = null,
    val shortages: CommandCostShortages = CommandCostShortages.None,
)

internal enum class HudActionGlyph { WAYPOINT, RALLY, UPGRADE }
internal data class CommandFrame(
    val title: String = "", val health: Float = 0f,
    val shield: Float = 0f, val count: Int = 0, val buttons: List<CommandButton> = emptyList(),
    val info: () -> CommandInfo = { CommandInfo(title, "") }
)

/** Callback lambdas are fresh snapshots, but must not force a static canvas to redraw each tick. */
internal fun sameCommandVisuals(a: CommandFrame, b: CommandFrame): Boolean =
    a.title == b.title && a.health == b.health && a.shield == b.shield && a.count == b.count &&
            a.buttons.size == b.buttons.size && a.buttons.indices.all { index ->
        val x = a.buttons[index]
        val y = b.buttons[index]
        x.label == y.label && x.heightScale == y.heightScale && x.columns == y.columns &&
                x.enabled == y.enabled && x.progress == y.progress && x.cooldown == y.cooldown && x.queued == y.queued && x.image === y.image &&
                x.showInfo == y.showInfo && x.glyph == y.glyph && x.canAdd == y.canAdd &&
                x.identity == y.identity && x.clickSound === y.clickSound && x.dimmed == y.dimmed &&
                x.hotkey == y.hotkey && x.category == y.category && x.techLevel == y.techLevel &&
                x.usable == y.usable && x.flash == y.flash && x.active == y.active &&
                x.demoBlocked == y.demoBlocked && x.pendingProduction == y.pendingProduction &&
                x.opensBuildingPlacement == y.opensBuildingPlacement && x.opensTouchQueue == y.opensTouchQueue &&
                x.economy == y.economy && x.shortages == y.shortages
    }

internal data class CommandInfo(val title: String, val text: AnnotatedString) {
    constructor(title: String, text: String) : this(title, AnnotatedString(text))
}

internal data class CommandTooltip(
    val anchor: Rect, val title: String, val text: AnnotatedString, val pointerY: Float? = null,
) {
    constructor(anchor: Rect, title: String, text: String, pointerY: Float? = null) :
        this(anchor, title, AnnotatedString(text), pointerY)
}

internal data class CommandCostShortages(
    val upfront: Set<CustomResource>,
    val streaming: Set<CustomResource>,
) {
    companion object {
        val None = CommandCostShortages(emptySet(), emptySet())
    }
}

private val shortageColor = Color(0xffff6262)

/** Compare amounts against the action's owner, independently of unlocks and production capacity. */
internal fun hudActionCostShortages(
    action: SpecialAction,
    source: com.corrodinggames.rts.game.units.Unit?,
): CommandCostShortages {
    val wrapped = action as? IndirectedAction
    val command = wrapped?.wrappedAction ?: action
    val owner = wrapped?.targetUnit ?: source ?: return CommandCostShortages.None
    val team = owner.team ?: return CommandCostShortages.None
    if (command.actionCategory == SpecialActionCategory.infoOnlyStockpile) return CommandCostShortages.None

    val upfront = buildSet {
        command.customPrice?.forEachCostAmount { resource, amount ->
            if (resource === CustomResource.CREDITS) {
                if (!team.canAfford(amount) || !team.canAffordIncludingPending(amount.toInt())) add(resource)
            } else if (resource.getAmount(owner) < amount) add(resource)
        }
    }
    val streaming = buildSet {
        command.secondaryPrice?.forEachCostAmount { resource, amount ->
            if (resource.getAmount(owner) < amount) add(resource)
        }
    }
    return if (upfront.isEmpty() && streaming.isEmpty()) CommandCostShortages.None
    else CommandCostShortages(upfront, streaming)
}

internal fun hudActionInfo(
    action: SpecialAction,
    source: com.corrodinggames.rts.game.units.Unit?,
    shortages: CommandCostShortages = hudActionCostShortages(action, source),
): CommandInfo {
    val wrapped = action as? IndirectedAction
    val command = wrapped?.wrappedAction ?: action
    val owner = wrapped?.targetUnit ?: source
    if (command is UnitInfoBox && command.showTeamInfo) {
        val details = owner?.team?.let { team ->
            val engine = GameEngine.getInstance()
            if (engine?.playerTeam == null) team.name.orEmpty()
            else engine.interfaceEngine.battleInterface.getTeamInfoText(team).trimEnd()
        }.orEmpty()
        return CommandInfo(readI18n("rwpp.hud.tooltip.unitOwner"), details)
    }
    val title = if (command === HoldFireAction) readI18n("rwpp.hud.holdFire.enable")
    else command.getTooltipTitle(owner).orEmpty()
    val showsPrice = command.actionCategory != SpecialActionCategory.infoOnlyStockpile
    val price = if (showsPrice) command.customPrice?.tooltipCostText(shortages.upfront) else null
    val streaming = if (showsPrice) command.secondaryPrice?.tooltipCostText(shortages.streaming) else null
    val economy = if (
        runCatching { io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>().showFactoryEconomy }
            .getOrDefault(true)
    ) FactoryEconomySampler().apply { beginFrame(source, System.nanoTime()) }.sample(action) else null
    return CommandInfo(title, buildAnnotatedString {
        if (price != null) appendTooltipCost("rwpp.hud.tooltip.cost", price)
        if (streaming != null) appendTooltipCost("rwpp.hud.tooltip.streamingCost", streaming)
        listOfNotNull(
            economy?.let { readI18n("rwpp.hud.tooltip.factoryEconomy", it.perMinute, it.factories) },
            BattleInterface.getSpecialActionQueueTimeText(action, false),
            owner?.takeIf { command is UnitTypeInfo }?.let { BattleInterface.getUnitInfoStatsText(it, false, true, false) },
            command.getDisplayDescriptionForUnit(owner),
        ).filter(String::isNotBlank).forEach {
            if (length > 0) append('\n')
            append(it)
        }
    })
}

private inline fun CustomPrice.forEachCostAmount(consume: (CustomResource, Double) -> Unit) {
    if (credits > 0) consume(CustomResource.CREDITS, credits.toDouble())
    if (energy > 0) consume(CustomResource.ENERGY, energy.toDouble())
    if (hp > 0) consume(CustomResource.HP, hp.toDouble())
    if (shield > 0) consume(CustomResource.SHIELD, shield.toDouble())
    if (ammo > 0) consume(CustomResource.AMMO, ammo.toDouble())
    if (!customResources.isEmpty) for (value in customResources.values) {
        val entry = value as? StoredResource ?: continue
        if (entry.amount > 0) consume(entry.resource, entry.amount)
    }
}

private fun CustomPrice.tooltipCostText(shortages: Set<CustomResource>): AnnotatedString? = buildAnnotatedString {
    forEachCostAmount { resource, amount ->
        if (length > 0) append(" | ")
        val start = length
        append(resource.formatAmountWithLabel(amount, false, false))
        if (resource in shortages) addStyle(SpanStyle(color = shortageColor), start, length)
    }
}.takeIf { it.isNotEmpty() }

private fun AnnotatedString.Builder.appendTooltipCost(key: String, cost: AnnotatedString) {
    if (length > 0) append('\n')
    val label = readI18n(key, "\uFFFC")
    append(label.substringBefore('\uFFFC'))
    append(cost)
    append(label.substringAfter('\uFFFC', ""))
}

internal fun commandButtonCaption(label: String, creditsShort: Boolean): AnnotatedString = buildAnnotatedString {
    append(label)
    val priceStart = label.lastIndexOf('\n') + 1
    if (creditsShort && priceStart > 0 && label.getOrNull(priceStart) == '$') {
        addStyle(SpanStyle(color = shortageColor), priceStart, length)
    }
}
internal object CommandPointer {
    var inPanel = false
}

internal object CommandHover {
    val current = mutableStateOf<CommandTooltip?>(null)

    fun clear() {
        current.value = null
    }
}

/** Hover bookkeeping for the information HUD; kept out of composition state to avoid redraw churn. */
internal class CommandHoverState {
    var frame: CommandFrame? = null
    var target = -2
    var point: Offset? = null
    var mouse = false
}

/** A draw-phase snapshot; native image requests use only buttons currently in the viewport. */
internal class CommandVisibility {
    @Volatile
    var ids: Set<String> = emptySet()
}

internal class CommandGeometry {
    var frame = CommandFrame()
    var rects: List<HudActionRect> = emptyList()
    var scroll by mutableFloatStateOf(0f)
    var top = 0f
    var width = 0f
    var height = 0f
    var contentHeight = 0f
    var scrollbarHitWidth = 0f
    private var layoutSizes: List<HudActionSize> = emptyList()
    private var layoutRowHeight = 0
    private var layoutGap = 0

    fun updateLayout(next: CommandFrame, width: Float, height: Float, top: Float, rowHeight: Int, gap: Int, scrollbar: Float) {
        frame = next
        if (this.width == width && this.height == height && this.top == top && layoutRowHeight == rowHeight &&
            layoutGap == gap && layoutSizes.size == next.buttons.size && next.buttons.indices.all {
                layoutSizes[it].heightScale == next.buttons[it].heightScale && layoutSizes[it].columns == next.buttons[it].columns
            }
        ) return
        this.width = width
        this.height = height
        this.top = top
        layoutRowHeight = rowHeight
        layoutGap = gap
        layoutSizes = next.buttons.map { HudActionSize(it.heightScale, it.columns) }
        val fullWidth = nativeHudActionLayout(width.toInt(), rowHeight, 2, layoutSizes, gap)
        val needsScrollbar = fullWidth.any { it.y + it.height > height - top }
        scrollbarHitWidth = if (needsScrollbar) scrollbar else 0f
        rects = if (needsScrollbar) nativeHudActionLayout(
            (width - scrollbar).roundToInt().coerceAtLeast(1), rowHeight, 2, layoutSizes, gap,
        ) else fullWidth
        contentHeight = (rects.maxOfOrNull { it.y + it.height } ?: 0).toFloat()
    }
    val offset get() = scroll.coerceIn(0f, (contentHeight - (height - top)).coerceAtLeast(0f))
    fun hit(point: Offset): Int? {
        if (point.y !in top..<height) return null
        val y = point.y - top + offset
        return rects.indexOfFirst { point.x >= it.x && point.x < it.x + it.width && y >= it.y && y < it.y + it.height }
            .takeIf { it >= 0 }
    }

    fun moveScroll(delta: Float) {
        scroll = (offset + delta).coerceIn(0f, (contentHeight - (height - top)).coerceAtLeast(0f))
    }

    fun scrollbar(minThumbHeight: Float): CommandScrollbar? {
        val trackHeight = (height - top).coerceAtLeast(0f)
        if (trackHeight <= 0f || contentHeight <= trackHeight) return null
        val thumbHeight = (trackHeight * trackHeight / contentHeight)
            .coerceAtLeast(minThumbHeight)
            .coerceAtMost(trackHeight)
        val maxOffset = (contentHeight - trackHeight).coerceAtLeast(0f)
        val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val thumbTop = top + if (maxOffset > 0f) thumbTravel * offset / maxOffset else 0f
        return CommandScrollbar(top, trackHeight, thumbTop, thumbHeight, maxOffset)
    }

    fun scrollbarGrabOffset(point: Offset, minThumbHeight: Float): Float? {
        if (point.x < width - scrollbarHitWidth || hit(point) != null) return null
        val scrollbar = scrollbar(minThumbHeight) ?: return null
        if (point.y < scrollbar.trackTop || point.y > scrollbar.trackTop + scrollbar.trackHeight) return null
        return if (point.y in scrollbar.thumbTop..(scrollbar.thumbTop + scrollbar.thumbHeight)) {
            point.y - scrollbar.thumbTop
        } else scrollbar.thumbHeight / 2f
    }

    fun dragScrollbar(pointerY: Float, grabOffset: Float, minThumbHeight: Float) {
        val scrollbar = scrollbar(minThumbHeight) ?: return
        val travel = (scrollbar.trackHeight - scrollbar.thumbHeight).coerceAtLeast(0f)
        if (travel <= 0f || scrollbar.maxOffset <= 0f) {
            scroll = 0f
            return
        }
        val thumbTop = (pointerY - grabOffset - scrollbar.trackTop).coerceIn(0f, travel)
        scroll = (thumbTop / travel * scrollbar.maxOffset).coerceIn(0f, scrollbar.maxOffset)
    }

    fun visibleIds(): Set<String> = rects.indices.asSequence().filter { index ->
        val rect = rects[index]
        val y = top + rect.y - offset
        y + rect.height > top && y < height
    }.mapNotNull { frame.buttons.getOrNull(it)?.identity }.toSet()
}

internal data class CommandScrollbar(
    val trackTop: Float,
    val trackHeight: Float,
    val thumbTop: Float,
    val thumbHeight: Float,
    val maxOffset: Float,
)

private class HudActionCallbacks(
    val action: SpecialAction,
    val primary: com.corrodinggames.rts.game.units.Unit?,
    val primaryType: com.corrodinggames.rts.game.units.UnitTypeInterface?,
    val selection: List<com.corrodinggames.rts.game.units.Unit>,
    val identity: String,
    val source: () -> com.corrodinggames.rts.game.units.Unit?,
    val info: () -> CommandInfo,
    val activate: (Boolean) -> Unit,
    val activateSalvo: (Boolean) -> Unit,
) {
    val salvoIdentity = "$identity:salvo"
    val layoutButton = CommandButton(
        label = "", heightScale = action.buttonWidthScale, columns = action.buttonColumnSpan,
        enabled = false, identity = identity, info = info, activate = activate,
        opensBuildingPlacement = action.actionType == ActionType.placeBuilding,
    )
    val salvoInfo: () -> CommandInfo = {
        val details = info()
        details.copy(title = readI18n("rwpp.hud.targetedSalvo", details.title))
    }
    val salvoLayoutButton = layoutButton.copy(identity = salvoIdentity, info = salvoInfo, activate = activateSalvo)
    private var cachedPrice = Int.MIN_VALUE
    private var cachedLabel = ""
    private var cachedDisplayName: String? = null
    private var cachedTooltipTitle: String? = null
    private var lastNameSampleAtNanos = Long.MIN_VALUE
    var glyph: HudActionGlyph? = null
        private set

    fun label(price: Int, nowNanos: Long): String {
        val priceChanged = price != cachedPrice
        val sampleDue = hudActionLabelSampleDue(lastNameSampleAtNanos, nowNanos)
        if (priceChanged || sampleDue) {
            val owner = source()
            val displayName = if (action.actionType == ActionType.infoOnly) action.displayNameWithCount
            else hudActionLabelForUnit(action, owner)
            val tooltipTitle = if (action.actionType == ActionType.placeBuilding) ""
            else owner?.let { action.getTooltipTitle(it) }.orEmpty()
            if (displayName != cachedDisplayName || tooltipTitle != cachedTooltipTitle) {
                cachedDisplayName = displayName
                cachedTooltipTitle = tooltipTitle
                glyph = action.aeroCommandGlyph(displayName, tooltipTitle)
            }
            cachedPrice = price
            cachedLabel = displayName + if (price > 0) $$"\n$$${price}" else ""
            if (sampleDue) lastNameSampleAtNanos = nowNanos
        }
        return cachedLabel
    }
}

internal fun hudActionLabelSampleDue(lastSampleAtNanos: Long, nowNanos: Long): Boolean =
    lastSampleAtNanos == Long.MIN_VALUE || nowNanos - lastSampleAtNanos >= 250_000_000L

internal fun mayReuseCommandButton(callbacksReplaced: Boolean, visualsMatch: Boolean): Boolean =
    !callbacksReplaced && visualsMatch

/** Reuses callbacks and buttons privately, publishing a detached read-only list only on change. */
private class CommandButtonFrameBuilder {
    private val callbacks = ArrayList<HudActionCallbacks>()
    private val buttonScratch = ArrayList<CommandButton>()
    private var published: List<CommandButton> = emptyList()
    private val economy = FactoryEconomySampler()
    private var hotkeyActions: List<SpecialAction>? = null
    private var hotkeySelection: List<com.corrodinggames.rts.game.units.Unit>? = null
    private var nextHotkeyRefresh = 0L
    private var nextVisualRefresh = 0L
    private var visibleActions: Set<String>? = null
    private var queuePanelIdentity: String? = null
    private var hotkeys: List<CommandHotkey> = emptyList()

    fun pollHotkeys(engine: GameEngine, selection: List<com.corrodinggames.rts.game.units.Unit>,
                    actions: List<SpecialAction>, now: Long) {
        if (hotkeyActions !== actions || hotkeySelection !== selection || now >= nextHotkeyRefresh) {
            hotkeys = commandHotkeys(engine, actions)
            hotkeyActions = actions
            hotkeySelection = selection
            nextHotkeyRefresh = now + 100_000_000L
        }
        activateCommandHotkeys(hotkeys, actions, selection)
    }

    fun visualsDue(now: Long, selectionSize: Int, changed: Boolean, visibleIds: Set<String>, queueIdentity: String?): Boolean {
        if (!changed && now < nextVisualRefresh && visibleActions == visibleIds && queuePanelIdentity == queueIdentity) {
            return false
        }
        nextVisualRefresh = now + if (selectionSize >= 80) 83_333_333L else 33_333_333L
        visibleActions = visibleIds
        queuePanelIdentity = queueIdentity
        return true
    }

    fun build(
        engine: GameEngine,
        primary: com.corrodinggames.rts.game.units.Unit?,
        selection: List<com.corrodinggames.rts.game.units.Unit>,
        actions: List<SpecialAction>,
        touch: Boolean,
        productionSelection: HudProductionSelection,
        visibleIds: Set<String>,
        displayedImages: MutableMap<String, ImageBitmap>,
    ): List<CommandButton> {
        while (callbacks.size > actions.size) callbacks.removeAt(callbacks.lastIndex)
        buttonScratch.clear()
        val nowNanos = System.nanoTime()
        val showEconomy = io.github.rwpp.logic.GameVisualSettings.get()?.showFactoryEconomy ?: true
        economy.beginFrame(if (showEconomy) primary else null, nowNanos)
        val primaryType = primary?.unitTypeInterface
        val productionId = productionSelection.identity
        val allowSalvo = !engine.replayEngine.isPlayingReplay && !isAeroLiveObserver(engine)
        for (index in actions.indices) {
            val action = actions[index]
            var callbacksForAction = callbacks.getOrNull(index)
            var callbacksReplaced = false
            if (callbacksForAction == null || callbacksForAction.action !== action ||
                callbacksForAction.primary !== primary || callbacksForAction.primaryType !== primaryType ||
                callbacksForAction.selection !== selection
            ) {
                val created = createCallbacks(action, primary, selection, touch, productionSelection)
                callbacksForAction = created
                callbacksReplaced = true
                if (index < callbacks.size) callbacks[index] = created else callbacks.add(created)
            }
            val actionCallbacks = callbacksForAction

            val identity = actionCallbacks.identity
            val showSalvo = allowSalvo && TargetedSalvo.supports(engine.interfaceEngine, action, selection)
            val visible = identity in visibleIds || showSalvo && actionCallbacks.salvoIdentity in visibleIds
            if (!visible && identity != productionId) {
                buttonScratch.add(actionCallbacks.layoutButton)
                if (showSalvo) buttonScratch.add(actionCallbacks.salvoLayoutButton)
                continue
            }
            // Native resolves permission and affordability across the whole selection. Asking only
            // the primary unit disables an upgrade that another selected unit can perform, which is
            // why a mixed level selection could not upgrade from the mouse but still did from keys.
            val unlocked = hudActionUnlockedForSelection(action, selection)
            val available = hudActionAvailableNow(engine, action, unlocked)
            val queued = if (action.actionType == ActionType.placeBuilding) 0
            else selection.sumOf { hudActionCountForUnit(action, it, true) }
            val pending = OverflowProductionQueue.pendingCount(action, selection)
            val holdFire = if (action === HoldFireAction) HoldFireAction.state(engine, selection) else null
            val label = if (action === HoldFireAction) HoldFireAction.buttonLabel(holdFire)
            else actionCallbacks.label(action.priceValue, nowNanos)
            val glyph = actionCallbacks.glyph
            val owner = actionCallbacks.source()
            val shortages = hudActionCostShortages(action, owner)
            val image = if (owner != null && glyph == null && visible) {
                ActionImages.load(action, owner, visible = true)?.also { displayedImages[identity] = it }
                    ?: displayedImages[identity]
            } else null
            val queuePanel = hudUsesTouchQueuePanel(
                touch, action.actionType, action.hasPersistentTooltip(),
                touch && hudActionQueueEntry(action, selection),
            )
            val canAdd = available || unlocked && OverflowProductionQueue.supports(action, selection)
            val enabled = canAdd || pending > 0 || queuePanel && queued > 0 ||
                    action.actionType == ActionType.placeBuilding && unlocked
            val nativeProgress = hudActionProgressForSelection(action, selection)
            val progress = nativeProgress.coerceIn(0f, 1f)
            val cooldown = hudActionCooldownForSelection(action, nativeProgress)
            val showInfo = action.actionType != ActionType.infoOnly ||
                    !touch && (action is UnitTypeInfo || action is UnitInfoBox)
            val clickSound = if (queuePanel || usesNativeActionFeedback(action.actionType)) null else io.github.rwpp.sound.Sounds.select
            val dimmed = !available || action.isDimmedInActionBar
            // renderActionBar paints the surface from isSpecialActionUsableBySelection and only the
            // border pulse from isSpecialActionUsable. Using the pulse as "usable" turned every
            // unaffordable action red.
            val usable = hudActionAffordableForSelection(action, selection)
            val flash = runCatching { engine.interfaceEngine.isSpecialActionUsable(action) }
                .getOrDefault(false)
            val category = action.actionCategory
            val salvoActive = TargetedSalvo.isActive(engine.interfaceEngine, action)
            val active = holdFire == HoldFireState.ON ||
                    engine.interfaceEngine.activeSpecialAction === action && !salvoActive
            val demoBlocked = BattleInterface.isSpecialActionDemoBlocked(action)
            val techLevel = action.unitType?.techLevel ?: action.techLevel
            val economyValue = economy.sample(action)
            val old = published.getOrNull(buttonScratch.size)
            val hotkey = hotkeys.getOrNull(index)?.button?.getBindingLabel()
                ?.takeUnless { it.substringAfterLast('+').equals("UNKNOWN", ignoreCase = true) }.orEmpty()
            buttonScratch.add(
                if (old != null && mayReuseCommandButton(
                        callbacksReplaced,
                        sameCommandButtonVisuals(
                            old, label, action.buttonWidthScale, action.buttonColumnSpan, enabled, progress, queued,
                            image, showInfo, glyph, canAdd, clickSound, identity, dimmed, hotkey,
                            category, techLevel, usable, flash, active, demoBlocked, cooldown, pending,
                            action.actionType == ActionType.placeBuilding, queuePanel, economyValue, shortages,
                        ),
                    )
                ) old else CommandButton(
                    label = label,
                    heightScale = action.buttonWidthScale,
                    columns = action.buttonColumnSpan,
                    enabled = enabled,
                    progress = progress,
                    cooldown = cooldown,
                    queued = queued,
                    image = image,
                    info = actionCallbacks.info,
                    activate = actionCallbacks.activate,
                    showInfo = showInfo,
                    glyph = glyph,
                    canAdd = canAdd,
                    identity = identity,
                    clickSound = clickSound,
                    dimmed = dimmed,
                    hotkey = hotkey,
                    category = category,
                    techLevel = techLevel,
                    usable = usable,
                    flash = flash,
                    active = active,
                    demoBlocked = demoBlocked,
                    pendingProduction = pending,
                    opensBuildingPlacement = action.actionType == ActionType.placeBuilding,
                    opensTouchQueue = queuePanel,
                    economy = economyValue,
                    shortages = shortages,
                )
            )
            if (showSalvo) {
                val salvoLabel = readI18n("rwpp.hud.targetedSalvo", label)
                val salvo = buttonScratch.last().copy(
                    label = salvoLabel,
                    identity = actionCallbacks.salvoIdentity,
                    hotkey = "",
                    info = actionCallbacks.salvoInfo,
                    activate = actionCallbacks.activateSalvo,
                    active = salvoActive,
                    opensTouchQueue = false,
                )
                val previous = published.getOrNull(buttonScratch.size)
                buttonScratch.add(if (previous == salvo) previous else salvo)
            }
        }
        if (published.size == buttonScratch.size && published.indices.all { published[it] === buttonScratch[it] }) {
            return published
        }
        // Never expose the mutable scratch list to Compose; this copy is immutable by ownership.
        published = ArrayList(buttonScratch)
        return published
    }

    private fun createCallbacks(
        action: SpecialAction,
        primary: com.corrodinggames.rts.game.units.Unit?,
        selection: List<com.corrodinggames.rts.game.units.Unit>,
        touch: Boolean,
        productionSelection: HudProductionSelection,
    ): HudActionCallbacks {
        val identity = "${System.identityHashCode(primary)}:${action.actionIdString}"
        val source by lazy(LazyThreadSafetyMode.NONE) {
            when (action) {
                is UnitTypeInfo -> selection.firstOrNull { it.unitTypeInterface === action.unitType }
                is UnitInfoBox -> selection.firstOrNull { it is OrderableUnit } ?: primary
                is IndirectedAction -> action.targetUnit
                else -> selection.firstOrNull {
                    runCatching { it.getSpecialAction(action.storedActionId) === action }.getOrDefault(false)
                }
                    ?: primary
            }
        }
        val info = { hudActionInfo(action, source) }
        val activate: (Boolean) -> Unit = { remove ->
            val queuePanel = hudUsesTouchQueuePanel(
                touch, action.actionType, action.hasPersistentTooltip(),
                touch && hudActionQueueEntry(action, selection),
            )
            if (!remove && queuePanel) {
                val repeat = productionSelection.tap(identity)
                if (repeat) executeHudAction(action, selection, false)
                else io.github.rwpp.sound.Sounds.playSound(io.github.rwpp.sound.Sounds.openPopup)
            } else {
                productionSelection.clear()
                executeHudAction(action, selection, remove)
            }
        }
        val activateSalvo: (Boolean) -> Unit = { remove ->
            if (!remove) {
                productionSelection.clear()
                executeHudAction(action, selection, remove = false, salvo = true)
            }
        }
        return HudActionCallbacks(
            action, primary, primary?.unitTypeInterface, selection, identity, { source }, info, activate, activateSalvo,
        )
    }
}

private fun hudActionAvailableNow(
    engine: GameEngine,
    action: SpecialAction,
    unlocked: Boolean,
): Boolean = action.actionType == ActionType.infoOnly ||
        unlocked && hudActionAvailable(action) {
    engine.interfaceEngine.isSpecialActionAvailable(action, false)
}

/**
 * Native decides the queue panel from a per-unit count. Asking the button's own action about a
 * foreign unit is what crashed on a modded selection, so only owners resolving that action answer.
 */
private fun hudActionQueueEntry(
    action: SpecialAction,
    selection: List<com.corrodinggames.rts.game.units.Unit>,
): Boolean {
    for (unit in selection) {
        if (unit !is OrderableUnit) continue
        val owned = runCatching { unit.getSpecialAction(action.storedActionId) }.getOrNull() ?: continue
        if (runCatching { owned.getCountForUnit(unit, false) }.getOrDefault(-1) != -1) return true
    }
    return false
}

private fun sameCommandButtonVisuals(
    button: CommandButton,
    label: String,
    heightScale: Float,
    columns: Int,
    enabled: Boolean,
    progress: Float,
    queued: Int,
    image: ImageBitmap?,
    showInfo: Boolean,
    glyph: HudActionGlyph?,
    canAdd: Boolean,
    clickSound: io.github.rwpp.api.game.audio.GameSound?,
    identity: String,
    dimmed: Boolean,
    hotkey: String,
    category: SpecialActionCategory,
    techLevel: Int,
    usable: Boolean,
    flash: Boolean,
    active: Boolean,
    demoBlocked: Boolean,
    cooldown: Float,
    pendingProduction: Int,
    opensBuildingPlacement: Boolean,
    opensTouchQueue: Boolean,
    economy: FactoryEconomy?,
    shortages: CommandCostShortages,
): Boolean = button.label == label && button.heightScale == heightScale && button.columns == columns &&
        button.enabled == enabled && button.progress == progress && button.cooldown == cooldown && button.queued == queued && button.image === image &&
        button.showInfo == showInfo && button.glyph == glyph && button.canAdd == canAdd &&
        button.clickSound === clickSound && button.identity == identity && button.dimmed == dimmed &&
        button.hotkey == hotkey && button.category == category && button.techLevel == techLevel &&
        button.usable == usable && button.flash == flash && button.active == active &&
        button.demoBlocked == demoBlocked && button.pendingProduction == pendingProduction &&
        button.opensBuildingPlacement == opensBuildingPlacement && button.opensTouchQueue == opensTouchQueue &&
        button.economy == economy && button.shortages == shortages

private data class CommandHotkey(
    val button: InputControllerButton?,
    val nativeHandled: Boolean,
)

/**
 * The native action bar binds the first ten ordinary actions (custom actions and
 * building placements included) to InputController.unitActionButtons. Aero draws
 * its own buttons, so it has to ask the same resolver for the label it would have
 * printed above each key. The counter is reset by the native renderer each frame.
 */
private fun commandHotkeys(engine: GameEngine, actions: List<SpecialAction>): List<CommandHotkey> {
    if (actions.isEmpty() || !GameEngine.isDesktopVersion()) return emptyList()
    return try {
        val battle = engine.interfaceEngine.battleInterface
        val input = engine.inputController
        // InterfaceEngine.update consumes these dedicated buttons itself. Reclaim and
        // rally have no such consumer, so they rely on the replacement action bar.
        val nativeHandled = listOf(
            input.attackMoveButton, input.guardUnitButton, input.patrolButton,
            input.stopButton, input.pingMapButton, input.saveGameButton,
        )
        val allActions = ArrayList(actions)
        battle.resetActionButtonSlots()
        actions.mapIndexed { index, action ->
            val button = if (action === HoldFireAction) NativeGameHotkeys.holdFireBinding(input)
            else runCatching {
                battle.getHotkeyForSpecialAction(action, index, allActions)
            }.getOrNull()
            CommandHotkey(
                button = button,
                nativeHandled = action === HoldFireAction || nativeHandled.any { it === button },
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * Native keyboard activation lives inside BattleInterface.renderActionBar, which
 * Aero cancels. Reclaim, rally and custom action buttons have no consumer outside
 * that method, so they must be polled here even though they never occupy one of
 * the ten unitActionButtons slots. Buttons already handled by InterfaceEngine.update
 * are excluded to avoid running the action twice.
 */
private fun activateCommandHotkeys(
    hotkeys: List<CommandHotkey>,
    actions: List<SpecialAction>,
    selection: List<com.corrodinggames.rts.game.units.Unit>,
) {
    if (hotkeys.isEmpty() || !NativeHudVisibility.hidden || !GameEngine.isDesktopVersion()) return
    if (GameUiPauseState.blocked || isShowSendMessageDialog || UI.showPlayerList) return
    val pressed = ArrayList<SpecialAction>(1)
    for (index in hotkeys.indices) {
        val hotkey = hotkeys[index]
        if (index >= actions.size || hotkey.nativeHandled) continue
        if (hotkey.button?.isPressed() == true) pressed += actions[index]
    }
    pressed.forEach { executeHudAction(it, selection) }
}

/** One stable layout node. Selection changes alter pixels and hit targets, never a button-node tree. */
@Composable
internal fun CommandSurface(
    frame: State<CommandFrame>, editing: Boolean = false, modifier: Modifier = Modifier,
    visibility: CommandVisibility? = null,
    plannerOwnsBlank: () -> Boolean = io.github.rwpp.game.building.BuildingPlanningController::wantsDesktopMouse,
    placementActive: () -> Boolean = { false },
) {
    val geometry = remember { CommandGeometry() }
    val text = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val unavailableImageFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(0f) }
        )
    }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val consoleCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var pressed by remember { mutableStateOf(false) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val animations =
        runCatching { io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>().hudAnimations }.getOrDefault(true)
    var hoveredIndex by remember { mutableIntStateOf(-1) }
    var draggingScrollbar by remember { mutableStateOf(false) }
    var scrollbarGrabOffset by remember { mutableFloatStateOf(0f) }
    val hoverState = remember { CommandHoverState() }

    fun tooltip(point: Offset, info: CommandInfo): CommandTooltip {
        val y = consoleCoordinates[0]?.takeIf { it.isAttached }?.localToRoot(point)?.y
        return CommandTooltip(anchor, info.title, info.text, y)
    }

    /**
     * Only action buttons and the unit title strip have hover information.
     */
    fun showHoverInfo(point: Offset, force: Boolean = false) {
        if (placementActive()) {
            hoverState.frame = null
            CommandHover.clear()
            return
        }
        val currentFrame = frame.value
        val index = geometry.hit(point)
        val button = index?.let(currentFrame.buttons::getOrNull)
        val target = when {
            button?.opensTouchQueue == true && button.identity == HudQueuePopupTouch.selectedIdentity -> -2
            index != null && button?.showInfo == true -> index
            index == null && point.y in 0f..<geometry.top && point.x in 0f..<geometry.width -> -1
            else -> -2
        }
        if (!force && target == hoverState.target && currentFrame === hoverState.frame) return
        hoverState.frame = currentFrame
        hoverState.target = target
        hoverState.point = point
        CommandHover.current.value = when {
            target >= 0 -> tooltip(point, currentFrame.buttons[target].info())
            target == -1 -> tooltip(point, currentFrame.info())
            else -> null
        }
    }

    /** An external dismissal must not leave the cache claiming the panel is still shown. */
    fun invalidateHoverInfo() {
        hoverState.frame = null
        hoverState.target = -2
    }

    fun leaveHoverInfo() {
        invalidateHoverInfo()
        hoverState.point = null
        hoverState.mouse = false
        CommandHover.clear()
    }

    // A selection change replaces the frame while the pointer rests on the panel.
    LaunchedEffect(Unit) {
        snapshotFlow { frame.value }.collect {
            val point = hoverState.point
            if (hoverState.mouse && point != null) showHoverInfo(point, force = true)
        }
    }

    val buttonScale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else if (hoveredIndex >= 0) 1.02f else 1f,
        if (animations) androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = 800f
        ) else androidx.compose.animation.core.snap()
    )
    val feedback by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.35f else if (hoveredIndex >= 0) 0.2f else 0f,
        androidx.compose.animation.core.tween(if (animations) 150 else 0)
    )
    // Native pulses the border of a usable action with abs(cos(t)); the state is read inside the
    // draw scope so the pulse invalidates the canvas only, never the layout.
    val borderPulse = rememberInfiniteTransition(label = "actionBorder")
    val borderPulseState = borderPulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                1000,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "actionBorderPulse",
    )
    DisposableEffect(Unit) {
        onDispose {
            visibility?.ids = emptySet()
            CommandHover.clear(); CommandPointer.inPanel = false; HudQueuePopupTouch.actionAt = null
        }
    }
    Canvas(modifier.onGloballyPositioned {
        consoleCoordinates[0] = it
        anchor = it.boundsInRoot()
        val window = it.boundsInWindow()
        HudQueuePopupTouch.actionAt = { point ->
            geometry.hit(point - window.topLeft)?.let { index -> geometry.frame.buttons.getOrNull(index)?.identity }
        }
    }
        .pointerInput(editing) {
            CommandPointer.inPanel = false
            if (editing) return@pointerInput
            awaitPointerEventScope {
                var down: Offset? = null
                var last: Offset? = null
                var dragged = false
                var secondary = false
                var passThroughGesture = false
                val minimumThumbHeight = 18.dp.toPx()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val point = change.position
                    if (passThroughGesture && event.type == PointerEventType.Move) continue
                    if (passThroughGesture && event.type == PointerEventType.Release) {
                        passThroughGesture = false
                        continue
                    }
                    when (event.type) {
                        PointerEventType.Press -> {
                            val actionIndex = geometry.hit(point)
                            val grabOffset =
                                if (change.type == PointerType.Mouse && !isHudSecondaryPress(event) && actionIndex == null) {
                                    geometry.scrollbarGrabOffset(point, minimumThumbHeight)
                                } else null
                            passThroughGesture = actionIndex == null && grabOffset == null && plannerOwnsBlank()
                            if (passThroughGesture) {
                                // The blank press belongs to the building planner, but an open queue
                                // panel must still collapse: every click outside the console clears it.
                                HudQueuePopupTouch.dismiss()
                                down = null; last = null; pressed = false; pressedIndex = -1
                                CommandHover.current.value = null
                                invalidateHoverInfo()
                                CommandPointer.inPanel = false
                                continue
                            }
                            if (grabOffset != null) {
                                draggingScrollbar = true
                                scrollbarGrabOffset = grabOffset
                                dragged = true
                                pressed = false
                                pressedIndex = -1
                                down = null; last = null
                                geometry.dragScrollbar(point.y, grabOffset, minimumThumbHeight)
                            } else {
                                draggingScrollbar = false
                                down = point; last = point; dragged = false; secondary = isHudSecondaryPress(event)
                                pressedIndex = actionIndex ?: -1; pressed = true
                                val hitIdentity = geometry.frame.buttons.getOrNull(pressedIndex)?.identity
                                if (HudQueuePopupTouch.active && hitIdentity != HudQueuePopupTouch.selectedIdentity) HudQueuePopupTouch.dismiss()
                                if (pressedIndex < 0 && !hoverState.mouse) {
                                    CommandHover.current.value = null
                                    invalidateHoverInfo()
                                }
                            }
                            change.consume()
                        }

                        PointerEventType.Move -> {
                            if (draggingScrollbar && change.type == PointerType.Mouse) {
                                geometry.dragScrollbar(point.y, scrollbarGrabOffset, minimumThumbHeight)
                                change.consume()
                            } else if (down != null) {
                                if (!dragged && (point - down).getDistance() > viewConfiguration.touchSlop) {
                                    dragged = true
                                    HudQueuePopupTouch.dismiss()
                                    CommandHover.current.value = null
                                    invalidateHoverInfo()
                                }
                                if (dragged) geometry.moveScroll((last?.y ?: point.y) - point.y)
                                last = point; change.consume()
                            } else {
                                hoveredIndex = geometry.hit(point) ?: -1; CommandPointer.inPanel = true
                                if (change.type == PointerType.Mouse) {
                                    hoverState.mouse = true
                                    showHoverInfo(point)
                                }
                            }
                        }

                        PointerEventType.Release -> {
                            val touchRelease = change.type != PointerType.Mouse
                            var openingPlacement = false
                            var openingQueue = false
                            if (draggingScrollbar) {
                                if (change.type == PointerType.Mouse) {
                                    geometry.dragScrollbar(point.y, scrollbarGrabOffset, minimumThumbHeight)
                                }
                                draggingScrollbar = false
                            } else if (!dragged && down != null) geometry.hit(point)?.let { index ->
                                if (geometry.hit(down) == index) geometry.frame.buttons[index].let {
                                    openingPlacement = !secondary && it.enabled && it.opensBuildingPlacement
                                    openingQueue = !secondary && it.opensTouchQueue
                                    if (openingPlacement || openingQueue) {
                                        CommandHover.clear()
                                        invalidateHoverInfo()
                                    } else if (touchRelease) {
                                        if (!secondary && it.showInfo) {
                                            val info = it.info()
                                            CommandHover.current.value = tooltip(point, info)
                                        } else CommandHover.current.value = null
                                        invalidateHoverInfo()
                                    }
                                    if (it.enabled || secondary || openingQueue) {
                                        it.clickSound?.let { sound ->
                                            io.github.rwpp.sound.Sounds.playSound(if (secondary) io.github.rwpp.sound.Sounds.cancel else sound)
                                        }
                                        it.activate(secondary)
                                    }
                                }
                            }
                            if (!dragged && down != null && geometry.hit(point) == null) {
                                HudQueuePopupTouch.dismiss()
                                if (point.y < geometry.top && down.y < geometry.top && touchRelease) {
                                    val info = geometry.frame.info()
                                    CommandHover.current.value = tooltip(point, info)
                                    invalidateHoverInfo()
                                } else if (touchRelease) {
                                    CommandHover.current.value = null
                                    invalidateHoverInfo()
                                }
                            }
                            if (!touchRelease && !dragged && !openingPlacement && !openingQueue) {
                                showHoverInfo(point, force = true)
                            }
                            pressed = false
                            down = null; last = null; change.consume()
                        }

                        PointerEventType.Scroll -> {
                            HudQueuePopupTouch.dismiss()
                            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                            geometry.moveScroll(delta * 32.dp.toPx())
                            CommandHover.current.value = null
                            invalidateHoverInfo()
                            event.changes.forEach { it.consume() }
                        }

                        // Compose turns the first hover Move into Enter; the information HUD must
                        // open on that first event as well, otherwise it still needs a click.
                        PointerEventType.Enter -> {
                            CommandPointer.inPanel = true
                            if (change.type == PointerType.Mouse) {
                                hoverState.mouse = true
                                showHoverInfo(point)
                            }
                        }

                        PointerEventType.Exit -> {
                            hoveredIndex = -1; CommandPointer.inPanel = false
                            if (change.type == PointerType.Mouse) leaveHoverInfo()
                        }
                    }
                }
            }
        }) {
        val snapshot = frame.value
        geometry.updateLayout(snapshot, size.width, size.height, 4.dp.toPx(), 64.dp.roundToPx(), 4.dp.roundToPx(), 12.dp.toPx())
        visibility?.ids = geometry.visibleIds()
        fun panel(origin: Offset, extent: Size, accent: Color, active: Boolean = false) {
            val cut = minOf(8.dp.toPx(), extent.height / 3)
            val path = Path().apply {
                moveTo(origin.x + cut, origin.y); lineTo(origin.x + extent.width, origin.y)
                lineTo(origin.x + extent.width, origin.y + extent.height - cut)
                lineTo(origin.x + extent.width - cut, origin.y + extent.height)
                lineTo(origin.x, origin.y + extent.height); lineTo(origin.x, origin.y + cut); close()
            }
            drawPath(
                path, Brush.verticalGradient(
                    listOf(
                        colors.surfaceContainerHigh.copy(alpha = hudPanelAlpha(0.93f)),
                        colors.surfaceContainerLowest.copy(alpha = hudPanelAlpha(0.9f))
                    ), origin.y, origin.y + extent.height
                )
            )
            drawPath(path, accent.copy(alpha = if (active) 0.85f else 0.35f), style = Stroke(1.dp.toPx()))
            drawLine(
                accent,
                origin + Offset(cut, 0f),
                origin + Offset(minOf(extent.width * 0.35f, 40.dp.toPx()), 0f),
                2.dp.toPx()
            )
        }
        panel(Offset.Zero, size, colors.primary)

        // BattleInterface.renderActionBar paints a translucent solid, then a border that pulses
        // while the action is usable. Reproduced here so a modded action looks the same.
        fun actionBackground(origin: Offset, extent: Size, button: CommandButton, hovered: Boolean) {
            val usable = button.usable || button.category == SpecialActionCategory.infoOnlyStockpile
            var fill = when {
                button.demoBlocked -> Color(180, 100, 100, 100)
                button.cooldown > 0f -> Color(100, 100, 100, 70)
                usable -> Color(100, 100, 100, 70)
                else -> Color(170, 100, 100, 50)
            }
            if (hovered) fill = Color(100, 100, 200, 80)
            if (button.active) fill = Color(100, 200, 100, 80)
            if (button.dimmed) fill = fill.copy(alpha = fill.alpha * 0.7f)
            drawRect(fill, origin, extent)
        }

        fun actionBorder(origin: Offset, extent: Size, button: CommandButton) {
            // Only the hint pulse is white and animated. Affordability selects the fill, never the
            // border, exactly as renderActionBar keeps var53 and var58 separate.
            val pulse = kotlin.math.abs(kotlin.math.cos(borderPulseState.value * kotlin.math.PI.toFloat()))
            val color = when {
                button.dimmed -> Color(155, 155, 155, 50)
                button.flash -> Color(255, 255, 255, (100 + 150 * pulse).toInt().coerceIn(0, 255))
                else -> Color(0, 0, 0, 100)
            }
            drawRect(color, origin, extent, style = Stroke(1.dp.toPx()))
        }

        fun actionCaptionColor(button: CommandButton, fallback: Color): Color {
            val usable = button.usable || button.category == SpecialActionCategory.infoOnlyStockpile
            return when {
                button.category == SpecialActionCategory.rally -> Color.White
                button.category == SpecialActionCategory.upgrade ||
                        button.category == SpecialActionCategory.action ->
                    if (usable) Color(39, 202, 189) else Color(19, 101, 94)

                button.category == SpecialActionCategory.queueUnit && button.techLevel > 1 ->
                    if (usable) Color(235, 240, 30) else Color(117, 120, 15)

                button.category == SpecialActionCategory.infoOnly ||
                        button.category == SpecialActionCategory.infoOnlyNoBox ||
                        button.category == SpecialActionCategory.infoOnlyStockpile ->
                    Color(255, 255, 255, 155)

                !usable -> Color(0, 100, 0)
                else -> fallback
            }
        }

        fun label(
            value: AnnotatedString,
            position: Offset,
            width: Float,
            height: Float,
            font: Int = 11,
            color: Color = colors.onSurface
        ) {
            drawText(
                text, value, topLeft = position, size = Size(width.coerceAtLeast(1f), height.coerceAtLeast(1f)),
                style = TextStyle(
                    color = color, fontSize = font.sp,
                    shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 1f), 2f)
                ),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        clipRect(0f, geometry.top, size.width, size.height) {
            geometry.rects.forEachIndexed { i, r ->
                val y = geometry.top + r.y - geometry.offset
                if (y + r.height < geometry.top || y > size.height) return@forEachIndexed
                val button = snapshot.buttons[i]
                val origin = Offset(r.x.toFloat(), y)
                val extent = Size(r.width.toFloat(), r.height.toFloat())
                val cut = minOf(8.dp.toPx(), extent.height / 3)
                val buttonPath = Path().apply {
                    moveTo(origin.x + cut, origin.y); lineTo(origin.x + extent.width, origin.y)
                    lineTo(origin.x + extent.width, origin.y + extent.height - cut)
                    lineTo(origin.x + extent.width - cut, origin.y + extent.height)
                    lineTo(origin.x, origin.y + extent.height); lineTo(origin.x, origin.y + cut); close()
                }
                clipPath(buttonPath) {
                    val activeFeedback = i == if (pressed) pressedIndex else hoveredIndex
                    withTransform({
                        if (activeFeedback) scale(
                            buttonScale,
                            buttonScale,
                            origin + Offset(extent.width / 2f, extent.height / 2f)
                        )
                    }) {
                        actionBackground(origin, extent, button, activeFeedback)
                        if (button.cooldown > 0f) {
                            val remainingWidth = extent.width * button.cooldown.coerceIn(0f, 1f)
                            // The native remaining-time ratio decreases to zero. Keep the red fill
                            // confined to that width instead of tinting the whole disabled button.
                            drawRect(Color(170, 35, 35, 115), origin, Size(remainingWidth, extent.height))
                            drawLine(
                                Color(235, 100, 100, 190), origin + Offset(remainingWidth, 0f),
                                origin + Offset(remainingWidth, extent.height), 1.dp.toPx()
                            )
                        }
                        if (button.queued > 0) {
                            // The Aero queue badge keeps its accent outline so a queued count stays
                            // readable against the native translucent fill.
                            drawRect(colors.primary.copy(alpha = 0.75f), origin, extent, style = Stroke(1.dp.toPx()))
                        }
                        if (button.glyph != null) {
                            drawHudActionGlyph(button.glyph, origin, extent)
                        } else button.image?.let { image ->
                            // The caption overlays the sprite; short native action rows must not reserve
                            // nearly their entire height for text and squeeze the unit down to a few pixels.
                            val scale = minOf(
                                (extent.width - 8.dp.toPx()).coerceAtLeast(1f) / image.width,
                                (extent.height - 8.dp.toPx()).coerceAtLeast(1f) / image.height
                            )
                            val w = (image.width * scale).toInt().coerceAtLeast(1)
                            val h = (image.height * scale).toInt().coerceAtLeast(1)
                            drawImage(
                                image,
                                dstOffset = IntOffset(r.x + (r.width - w) / 2, (y + (extent.height - h) / 2f).toInt()),
                                dstSize = IntSize(w, h),
                                alpha = if (!button.dimmed) 1f else 0.4f,
                                colorFilter = if (button.dimmed) unavailableImageFilter else null
                            )
                        }
                        if (button.progress > 0) {
                            val progress = button.progress.coerceIn(0f, 1f)
                            drawRect(
                                colors.primary.copy(alpha = 0.23f),
                                origin + Offset(0f, extent.height * (1f - progress)),
                                Size(extent.width, extent.height * progress)
                            )
                            drawRect(
                                colors.primary, origin + Offset(0f, extent.height - 2.dp.toPx()),
                                Size(extent.width * progress, 2.dp.toPx())
                            )
                        }
                        if (activeFeedback) drawRect(colors.primary.copy(alpha = feedback), origin, extent)
                        actionBorder(origin, extent, button)
                        var economyReserved = 0f
                        button.economy?.let { economy ->
                            // Same square-cut chip language as the queue badge: dark translucent
                            // fill with the accent outline, so it reads on any unit sprite. It sits
                            // in the bottom-right, clear of the cut corner and the progress strip.
                            val layout = text.measure(
                                AnnotatedString(
                                    readI18n("rwpp.hud.factoryEconomy.badge", economy.perMinute, economy.factories)
                                ),
                                style = TextStyle(
                                    color = colors.primary, fontSize = 10.sp,
                                ),
                                maxLines = 1
                            )
                            val padding = 3.dp.toPx()
                            val chipWidth = layout.size.width + padding * 2
                            val chipHeight = layout.size.height + 2.dp.toPx()
                            val right = origin.x + extent.width - cut - 2.dp.toPx()
                            val left = (right - chipWidth).coerceAtLeast(origin.x + 2.dp.toPx())
                            val top = origin.y + extent.height - 4.dp.toPx() - chipHeight
                            drawRect(Color.Black.copy(alpha = 0.55f), Offset(left, top), Size(right - left, chipHeight))
                            drawRect(
                                colors.primary.copy(alpha = 0.75f), Offset(left, top), Size(right - left, chipHeight),
                                style = Stroke(1.dp.toPx())
                            )
                            drawText(layout, topLeft = Offset(left + padding, top + 1.dp.toPx()))
                            economyReserved = origin.x + extent.width - left
                        }
                        val captionHeight = minOf(30.dp.toPx(), extent.height - 4.dp.toPx())
                        label(
                            commandButtonCaption(button.label, CustomResource.CREDITS in button.shortages.upfront),
                            origin + Offset(6.dp.toPx(), extent.height - captionHeight),
                            // The caption shares the bottom edge with the economy chip.
                            (extent.width - 12.dp.toPx() - economyReserved).coerceAtLeast(1f),
                            captionHeight,
                            color = actionCaptionColor(
                                button,
                                if (!button.dimmed) colors.onSurface else colors.onSurfaceVariant,
                            )
                        )
                        val queueBadge = when {
                            button.queued > 0 && button.pendingProduction > 0 -> "${button.queued} +${button.pendingProduction}"
                            button.pendingProduction > 0 -> "+${button.pendingProduction}"
                            button.queued > 0 -> button.queued.toString()
                            else -> null
                        }
                        val badgeLayout = queueBadge?.let {
                            text.measure(
                                it, style = TextStyle(
                                    color = if (button.pendingProduction > 0) Color(0xffffcb74) else colors.onPrimary,
                                    fontSize = 10.sp,
                                ), maxLines = 1,
                            )
                        }
                        val badgeWidth = badgeLayout?.let { maxOf(28.dp.toPx(), it.size.width + 8.dp.toPx()) } ?: 0f
                        if (button.hotkey.isNotBlank()) {
                            val layout = text.measure(
                                AnnotatedString(button.hotkey),
                                style = TextStyle(color = Color(0xff8cf59a), fontSize = 10.sp),
                                maxLines = 1
                            )
                            drawText(layout, topLeft = origin + Offset(2.dp.toPx(), 3.dp.toPx()))
                        }
                        if (badgeLayout != null) {
                            val badge = origin + Offset(extent.width - badgeWidth - 6.dp.toPx(), 4.dp.toPx())
                            val badgeHeight = badgeLayout.size.height + 2.dp.toPx()
                            val badgeSize = Size(badgeWidth, badgeHeight)
                            val pending = button.pendingProduction > 0
                            drawRect(if (pending) Color(0xff13232d) else colors.primary, badge, badgeSize)
                            if (pending) drawRect(Color(0xffffcb74), badge, badgeSize, style = Stroke(1.dp.toPx()))
                            drawText(
                                badgeLayout,
                                topLeft = badge + Offset(4.dp.toPx(), (badgeHeight - badgeLayout.size.height) / 2)
                            )
                        }
                    }
                }
            }
        }
        geometry.scrollbar(18.dp.toPx())?.let { scrollbar ->
            drawRect(
                colors.outlineVariant.copy(alpha = 0.35f),
                Offset(size.width - 3.dp.toPx(), scrollbar.trackTop),
                Size(2.dp.toPx(), scrollbar.trackHeight)
            )
            drawRect(
                colors.primary.copy(alpha = 0.9f),
                Offset(size.width - 3.dp.toPx(), scrollbar.thumbTop),
                Size(2.dp.toPx(), scrollbar.thumbHeight)
            )
        }
    }
}

@Composable
internal fun GameScreen.AeroCommandConsole() {
    val model = remember { mutableStateOf(CommandFrame(title = readI18n("rwpp.hud.standby"))) }
    var commands by remember { mutableStateOf(emptyList<SpecialAction>(), referentialEqualityPolicy()) }
    val target = selectedUnits.firstOrNull()
    val touch = io.github.rwpp.appKoin.get<io.github.rwpp.AppContext>().isAndroid()
    val productionSelection = remember { HudProductionSelection() }
    val queueAction = commands.firstOrNull {
        productionSelection.identity == "${System.identityHashCode(target)}:${it.actionIdString}"
    }
    var showPingPicker by remember { mutableStateOf(false) }
    LaunchedEffect(target) { productionSelection.clear(); showPingPicker = false; CommandHover.current.value = null }
    val frameSelection = remember { arrayOf<List<com.corrodinggames.rts.game.units.Unit>>(emptyList()) }
    val visibility = remember { CommandVisibility() }
    val displayedImages = remember { HashMap<String, ImageBitmap>() }
    val imageTarget = remember { arrayOfNulls<com.corrodinggames.rts.game.units.Unit>(1) }
    val buttonFrameBuilder = remember { CommandButtonFrameBuilder() }
    val standbyTitle = remember { readI18n("rwpp.hud.standby") }
    val cachedFrameTitle = remember { arrayOf(standbyTitle) }
    val cachedTitlePrimary = remember { arrayOfNulls<com.corrodinggames.rts.game.units.Unit>(1) }
    val cachedTitleType = remember { arrayOfNulls<com.corrodinggames.rts.game.units.UnitTypeInterface>(1) }
    val cachedFrameInfo = remember { arrayOf({ CommandInfo(standbyTitle, "") }) }
    val cachedInfoPrimary = remember { arrayOfNulls<com.corrodinggames.rts.game.units.Unit>(1) }
    OnGameUpdate {
        val engine = GameEngine.getInstance()
        val selection = selectedUnits
        val primary = selection.firstOrNull()
        if (imageTarget[0] !== primary) {
            displayedImages.clear()
            imageTarget[0] = primary
        }
        val visibleIds = visibility.ids
        val selectionChanged = frameSelection[0] !== selection
        if (selectionChanged) frameSelection[0] = selection
        val nextCommands = if (!sandboxNativeEditorActionsAllowed(engine) &&
            selection.any { it is com.corrodinggames.rts.game.units.EditorUnit }
        ) emptyList() else primary?.let { NativeHudActions.selection(it, selection) } ?: emptyList()
        val actionsChanged =
            commands.size != nextCommands.size || commands.indices.any { commands[it] !== nextCommands[it] }
        if (actionsChanged) commands = nextCommands
        val commandActions = if (actionsChanged) nextCommands else commands
        val now = System.nanoTime()
        buttonFrameBuilder.pollHotkeys(engine, selection, commandActions, now)
        if (!buttonFrameBuilder.visualsDue(now, selection.size, selectionChanged || actionsChanged,
                visibleIds, productionSelection.identity)
        ) return@OnGameUpdate
        displayedImages.keys.retainAll(visibleIds)

        // Sandbox unit editing is its own HUD component; the console only draws command buttons.
        val primaryType = primary?.unitTypeInterface
        val title =
            if (primary === cachedTitlePrimary[0] && primaryType === cachedTitleType[0]) cachedFrameTitle[0] else {
                cachedTitlePrimary[0] = primary
                cachedTitleType[0] = primaryType
                (primaryType?.displayName ?: standbyTitle).also { cachedFrameTitle[0] = it }
            }
        val infoChanged = primary !== cachedInfoPrimary[0]
        if (infoChanged) {
            cachedInfoPrimary[0] = primary
            cachedFrameInfo[0] = {
                if (primary == null) CommandInfo(standbyTitle, "")
                else CommandInfo(primary.unitTypeInterface.displayName,
                    primary.unitTypeInterface.displayDescription + "\n" + BattleInterface.getUnitInfoStatsText(
                            primary, false, true, false
                        ),
                )
            }
        }
        val nextButtons = buttonFrameBuilder.build(
            engine, primary, selection, commandActions, touch, productionSelection,
            visibleIds, displayedImages,
        )
        val health = primary?.let { unitHealthRatio(it.hp, it.maxHp) } ?: 0f
        val shield = primary?.let { unitHealthRatio(it.shield, it.maxShield) } ?: 0f
        val currentFrame = model.value
        if (actionsChanged || selectionChanged || infoChanged || title != currentFrame.title ||
            health != currentFrame.health || shield != currentFrame.shield || selection.size != currentFrame.count ||
            nextButtons !== currentFrame.buttons
        ) {
            model.value = CommandFrame(title, health, shield, selection.size, nextButtons, cachedFrameInfo[0])
        }
    }
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(156.dp)) { AeroMinimap() }
        Spacer(Modifier.height(6.dp))
        val sandboxEngine = GameEngine.getInstance()
        val showSandboxControls = sandboxNativeEditorActionsAllowed(sandboxEngine) &&
                (selectedUnits.any { it is com.corrodinggames.rts.game.units.EditorUnit } ||
                        sandboxUnitDataUiAllowed(sandboxEngine))
        if (target != null || isEditMode || (showSandboxControls && !showPingPicker)) {
            if (touch && target !is com.corrodinggames.rts.game.units.EditorUnit) io.github.rwpp.widget.v2.AeroButton(
                readI18n("rwpp.hud.selection.clearAll"),
                onClick = NativeHudGroups::clearSelection, modifier = Modifier.fillMaxWidth(),
                clickSound = io.github.rwpp.sound.Sounds.cancel
            )
            if (showSandboxControls) SandboxForceBuildToggle()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CommandSurface(model, isEditMode, Modifier.fillMaxSize(), visibility, placementActive = {
                    GameEngine.getInstance()?.interfaceEngine?.activeSpecialAction?.actionType == ActionType.placeBuilding
                })
                TouchQueuePopup(
                    model.value.buttons.firstOrNull { it.identity == productionSelection.identity }
                        .takeUnless { isEditMode || showPauseMenu },
                    onAdd = { queueAction?.let { executeHudAction(it, selectedUnits, false) } },
                    onRemove = { queueAction?.let { executeHudAction(it, selectedUnits, true) } },
                    onClose = productionSelection::clear
                )
            }
        } else if (showPingPicker) {
            HudMapPingPicker(
                onDismiss = { showPingPicker = false },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                io.github.rwpp.widget.v2.AeroButton(
                    readI18n("rwpp.hud.shortcuts.chat"),
                    onClick = { isSendingTeamChat = false; showSendMessageDialog() },
                    modifier = Modifier.weight(1f),
                    clickSound = io.github.rwpp.sound.Sounds.openPopup
                )
                io.github.rwpp.widget.v2.AeroButton(
                    readI18n("rwpp.hud.shortcuts.ping"), onClick = { showPingPicker = true },
                    modifier = Modifier.weight(1f), clickSound = io.github.rwpp.sound.Sounds.select
                )
            }
        }
    }
}

internal fun hudActionGlyph(identity: String): HudActionGlyph? {
    val normalized = identity.lowercase()
    return when {
        "rally" in normalized || "集结" in normalized -> HudActionGlyph.RALLY
        "upgrade" in normalized || "升级" in normalized -> HudActionGlyph.UPGRADE
        "waypoint" in normalized || "patrol" in normalized || "路径点" in normalized || "巡逻" in normalized -> HudActionGlyph.WAYPOINT
        else -> null
    }
}

private fun SpecialAction.aeroCommandGlyph(displayName: String, tooltipTitle: String): HudActionGlyph? =
    hudActionGlyph("$actionIdString ${javaClass.simpleName} $displayName $tooltipTitle")

private fun DrawScope.drawHudActionGlyph(glyph: HudActionGlyph, origin: Offset, extent: Size) {
    val color = when (glyph) {
        HudActionGlyph.UPGRADE -> Color(0xffffd58a)
        HudActionGlyph.RALLY -> Color(0xff73e4c0)
        HudActionGlyph.WAYPOINT -> Color(0xffa8edff)
    }
    val center = Offset(origin.x + extent.width * 0.5f, origin.y + extent.height * 0.39f)
    val radius = minOf(extent.width, extent.height - 22.dp.toPx()).coerceAtLeast(24.dp.toPx()) * 0.22f
    drawCircle(Color(0xff061522).copy(alpha = 0.66f), radius * 1.8f, center)
    drawCircle(color.copy(alpha = 0.12f), radius * 1.55f, center, style = Stroke(1.5.dp.toPx()))
    val stroke = Stroke(width = maxOf(2.dp.toPx(), radius * 0.19f), cap = StrokeCap.Round)
    when (glyph) {
        HudActionGlyph.UPGRADE -> {
            val head = Path().apply {
                moveTo(center.x - radius * 0.58f, center.y - radius * 0.05f)
                lineTo(center.x, center.y - radius * 0.68f)
                lineTo(center.x + radius * 0.58f, center.y - radius * 0.05f)
            }
            drawPath(head, color, style = stroke)
            drawLine(
                color,
                Offset(center.x, center.y - radius * 0.55f),
                Offset(center.x, center.y + radius * 0.46f),
                stroke.width,
                cap = stroke.cap
            )
            drawLine(
                color.copy(alpha = 0.8f), Offset(center.x - radius * 0.56f, center.y + radius * 0.55f),
                Offset(center.x + radius * 0.56f, center.y + radius * 0.55f), stroke.width
            )
        }
        HudActionGlyph.WAYPOINT -> {
            val route = Path().apply {
                moveTo(center.x - radius * 0.68f, center.y + radius * 0.48f)
                lineTo(center.x - radius * 0.16f, center.y - radius * 0.13f)
                lineTo(center.x + radius * 0.29f, center.y + radius * 0.22f)
                lineTo(center.x + radius * 0.66f, center.y - radius * 0.47f)
            }
            drawCircle(color, radius * 0.15f, Offset(center.x - radius * 0.68f, center.y + radius * 0.48f))
            drawPath(route, color, style = stroke)
            drawLine(
                color, Offset(center.x + radius * 0.66f, center.y - radius * 0.47f),
                Offset(center.x + radius * 0.17f, center.y - radius * 0.44f), stroke.width, cap = stroke.cap
            )
            drawLine(
                color, Offset(center.x + radius * 0.66f, center.y - radius * 0.47f),
                Offset(center.x + radius * 0.63f, center.y + radius * 0.01f), stroke.width, cap = stroke.cap
            )
        }
        else -> {
            // A rally point is a destination marker, distinct from the moving route glyph used by
            // patrol/waypoint actions.
            drawCircle(color.copy(alpha = 0.9f), radius * 0.62f, center, style = Stroke(stroke.width))
            drawCircle(color.copy(alpha = 0.45f), radius * 0.34f, center, style = Stroke(stroke.width * 0.8f))
            drawCircle(color, radius * 0.12f, center)
            drawLine(
                color, Offset(center.x - radius * 0.94f, center.y),
                Offset(center.x - radius * 0.72f, center.y), stroke.width
            )
            drawLine(
                color, Offset(center.x + radius * 0.72f, center.y),
                Offset(center.x + radius * 0.94f, center.y), stroke.width
            )
            drawLine(
                color, Offset(center.x, center.y - radius * 0.94f),
                Offset(center.x, center.y - radius * 0.72f), stroke.width
            )
            drawLine(
                color, Offset(center.x, center.y + radius * 0.72f),
                Offset(center.x, center.y + radius * 0.94f), stroke.width
            )
        }
    }
}

@Composable
internal fun AeroQuickSelectRow() {
    val labels = listOf(
        readI18n("rwpp.hud.quickSelect.allShort"),
        readI18n("rwpp.hud.quickSelect.buildingsShort"),
        readI18n("rwpp.hud.quickSelect.navyShort"),
        readI18n("rwpp.hud.quickSelect.airShort"),
        readI18n("rwpp.hud.quickSelect.landShort"),
    )
    val descriptions = listOf(
        readI18n("rwpp.hud.quickSelect.all"),
        readI18n("rwpp.hud.quickSelect.buildings"),
        readI18n("rwpp.hud.quickSelect.navy"),
        readI18n("rwpp.hud.quickSelect.air"),
        readI18n("rwpp.hud.quickSelect.land"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AeroIconButton(
                    painterResource(Res.drawable.group_30),
                    onClick = { NativeHudGroups.quickSelect(index) },
                    modifier = Modifier.size(28.dp), contentDescription = descriptions[index],
                    size = 28.dp, iconSize = 16.dp,
                )
                androidx.compose.material3.Text(
                    label,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AeroUnitGroupRow(groups: List<NativeHudGroup>) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEach { group ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text(
                    "${group.index + 1} · ${group.count}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    AeroIconButton(
                        painterResource(Res.drawable.group_30),
                        onClick = { NativeHudGroups.select(group.index, addToSelection = false) },
                        modifier = Modifier.size(24.dp),
                        contentDescription = readI18n("rwpp.hud.groups.select", group.index + 1),
                        size = 24.dp,
                        iconSize = 14.dp,
                    )

                    if (Rwml.get().platform() == Platform.ANDROID) {
                        AeroIconButton(
                            rememberVectorPainter(Icons.Default.Add),
                            onClick = { NativeHudGroups.addSelection(group.index, replace = false) },
                            modifier = Modifier.size(24.dp),
                            contentDescription = readI18n("rwpp.hud.groups.add", group.index + 1),
                            size = 24.dp,
                            iconSize = 14.dp,
                        )
                        AeroIconButton(
                            rememberVectorPainter(Icons.Default.Check),
                            onClick = { NativeHudGroups.addSelection(group.index, replace = true) },
                            modifier = Modifier.size(24.dp),
                            contentDescription = readI18n("rwpp.hud.groups.replace", group.index + 1),
                            size = 24.dp,
                            iconSize = 14.dp,
                        )
                        AeroIconButton(
                            rememberVectorPainter(Icons.Default.Close),
                            onClick = { NativeHudGroups.clear(group.index) },
                            modifier = Modifier.size(24.dp),
                            contentDescription = readI18n("rwpp.hud.groups.clear", group.index + 1),
                            size = 24.dp,
                            iconSize = 14.dp,
                            isDanger = true,
                            clickSound = io.github.rwpp.sound.Sounds.cancel,
                        )
                    }
                }
            }
        }
    }
}

/** Independent, draggable HUD component; group commands never consume console space. */
@Composable
internal fun GameScreen.AeroGroupsPanel() {
    val settings = io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>()
    var groups by remember { mutableStateOf(emptyList<NativeHudGroup>()) }
    OnGameUpdate {
        groups = NativeHudGroups.snapshot(settings.maxDisplayUnitGroupCount)
    }
    if (groups.isNotEmpty()) AeroUnitGroupRow(groups)
}

@Composable
internal fun AeroQuickSelectionPanel() {
    AeroQuickSelectRow()
}
