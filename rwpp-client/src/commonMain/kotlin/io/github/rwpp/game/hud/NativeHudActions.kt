/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.nativeaccess.NativeBattleAccess
import io.github.rwpp.game.nativeaccess.NativeCommandsAccess
import com.corrodinggames.rts.game.units.Unit as GameUnit

internal data class HudActionSize(val heightScale: Float, val columns: Int)
internal data class HudActionRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Native resolves the action per unit and only then asks for its count. Modded units can share an
 * ActionId with an implementation that casts to a concrete unit type (Dropship.unload), so the
 * owner's own instance is the only safe target and a mismatch must degrade to a zero count instead
 * of failing the whole HUD frame.
 */
internal fun hudActionCountForUnit(action: SpecialAction, unit: GameUnit, includeQueued: Boolean): Int {
    val owned = runCatching { unit.getSpecialAction(action.storedActionId) }.getOrNull() ?: return 0
    return runCatching { owned.getCountForUnit(unit, includeQueued) }.getOrDefault(0).coerceAtLeast(0)
}

/** Native getSpecialActionProgress scans the whole selection and keeps the highest value. */
internal fun hudActionProgressForSelection(action: SpecialAction, units: List<GameUnit>): Float {
    if (action.isInstantAction) return -1f
    var progress = -1f
    val id = action.storedActionId
    for (unit in units) {
        if (unit !is OrderableUnit) continue
        val owned = runCatching { unit.getSpecialAction(id) }.getOrNull() ?: continue
        val value = runCatching { owned.getProgress(unit) }.getOrDefault(-1f)
        if (value > progress) progress = value
    }
    return progress
}

/** Production progress has native priority; cooldown applies only when no production bar exists. */
internal fun hudActionCooldownForSelection(action: SpecialAction, productionProgress: Float): Float {
    if (productionProgress >= 0f) return 0f
    val value = runCatching {
        (GameEngine.getInstance().interfaceEngine.battleInterface as NativeBattleAccess).rwppActionBlockFraction(action)
    }.getOrDefault(0f)
    return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

/** Native getDisplayNameWithCount scans every selected unit using one owner's implementation. */
internal fun hudActionLabelForUnit(action: SpecialAction, owner: GameUnit?, count: Int): String {
    val name = owner?.let(action::getDisplayNameForUnit) ?: action.displayName
    return name + if (count > 0) " ($count)" else ""
}

/** Native row packing: column override changes width; buttonWidthScale changes height. */
internal fun nativeHudActionLayout(
    width: Int, baseHeight: Int, defaultColumns: Int,
    actions: List<HudActionSize>, gap: Int = 4
): List<HudActionRect> {
    var x = 0;
    var y = 0;
    var rowHeight = 0
    return actions.map { spec ->
        val columns = spec.columns.takeIf { it > 0 } ?: defaultColumns.coerceAtLeast(1)
        val cellWidth = (width / columns).coerceAtLeast(1)
        val height =
            (baseHeight * (spec.heightScale.takeIf { it.isFinite() && it > 0 } ?: 1f).coerceIn(0.1f, 4f)).toInt()
                .coerceAtLeast(1)
        if (x > 0 && (x + cellWidth > width || height < rowHeight)) {
            x = 0; y += rowHeight + gap; rowHeight = 0
        }
        val rect = HudActionRect(x, y, (cellWidth - gap).coerceAtLeast(1), height)
        x += cellWidth
        rowHeight = maxOf(rowHeight, height)
        rect
    }
}

internal object NativeHudActions {
    // OnGameUpdate is the sole caller and runs on the game/UI thread. The scratch collections are
    // private and never escape; published is replaced with a separate copy when identities change.
    private val selectedScratch = ArrayList<OrderableUnit>()
    private val previousSelection = ArrayList<OrderableUnit>()
    private val actionScratch = ArrayList<SpecialAction>()
    private var previousPrimary: GameUnit? = null
    private var guardOffered = false
    private var published: List<SpecialAction> = emptyList()

    fun selection(primary: GameUnit, units: List<GameUnit>): List<SpecialAction> {
        val battle = GameEngine.getInstance().interfaceEngine.battleInterface
        selectedScratch.clear()
        for (unit in units) if (unit is OrderableUnit) selectedScratch.add(unit)
        val selectionChanged = previousPrimary !== primary || previousSelection.size != selectedScratch.size ||
                previousSelection.indices.any { previousSelection[it] !== selectedScratch[it] }

        actionScratch.clear()
        // Copy/filter the native reusable result before the next native action-bar call.
        val native = battle.getSpecialActionsForSelection(primary, selectedScratch)
        // getSpecialActionsForSelection already merges the selection the way the native bar does:
        // the primary unit's actions first, then every action id the other unit types add. Merging
        // again by category drops one of two upgrade levels and makes a mixed selection unable to
        // upgrade, so the published list stays exactly what the native bar would render.
        for (candidate in native) {
            val action = candidate as? SpecialAction ?: continue
            if (!(battle as NativeBattleAccess).rwppActionVisible(action, selectedScratch)) continue
            actionScratch.add(action)
        }
        val actionsChanged =
            published.size != actionScratch.size || published.indices.any { published[it] !== actionScratch[it] }
        if (selectionChanged || actionsChanged) {
            previousPrimary = primary
            previousSelection.clear()
            previousSelection.addAll(selectedScratch)
            // This list is detached from the scratch list and remains untouched after publication.
            published = ArrayList(actionScratch)
            guardOffered = published.any {
                it.actionType == com.corrodinggames.rts.game.units.actions.ActionType.guardUnit
            }
        }
        // Native action-bar rendering resets this latch, so keep synchronizing it each update.
        (GameEngine.getInstance().interfaceEngine as NativeCommandsAccess).rwppGuardOffered(
            guardOffered
        )
        return published
    }
}

internal fun hudQueueBatch(canQueue: Boolean, shift: Boolean, ctrl: Boolean): Int =
    if (!canQueue) 1 else if (ctrl) 10 else if (shift) 5 else 1
