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

/** Same action id can have different native owner-specific implementations (e.g. unload). */
internal fun hudActionCountForUnit(action: SpecialAction, unit: GameUnit, includeQueued: Boolean): Int =
    unit.getSpecialAction(action.actionId)?.getCountForUnit(unit, includeQueued)?.coerceAtLeast(0) ?: 0

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
        for (candidate in native) {
            val action = candidate as? SpecialAction ?: continue
            if ((battle as NativeBattleAccess).rwppActionVisible(action, selectedScratch)) actionScratch.add(action)
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
