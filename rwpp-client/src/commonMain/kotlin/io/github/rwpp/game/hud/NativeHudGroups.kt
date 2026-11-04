/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.MovementType
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.gui.UnitHotSelectionGroup
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.game.nativeaccess.NativeBattleAccess
import kotlinx.coroutines.launch

internal data class NativeHudGroup(val index: Int, val count: Int)

/** Snapshot on the game owner; mutations are posted through the same native game scope as HUD actions. */
internal object NativeHudGroups {
    private var cachedLimit = -1
    private var cachedSnapshot: List<NativeHudGroup> = emptyList()

    fun clearSelection() {
        GameScope.launch {
            GameEngine.getInstance().interfaceEngine.apply {
                cancelActiveAction()
                clearUnitSelection()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun groups(): List<UnitHotSelectionGroup> =
        (GameEngine.getInstance().interfaceEngine.battleInterface as NativeBattleAccess).rwppGroups() as List<UnitHotSelectionGroup>

    fun snapshot(limit: Int): List<NativeHudGroup> {
        val visibleCount = limit.coerceIn(0, 10)
        val nativeGroups = groups()
        val count = minOf(visibleCount, nativeGroups.size)
        if (cachedLimit == visibleCount && cachedSnapshot.size == count) {
            var unchanged = true
            for (index in 0 until count) {
                if (cachedSnapshot[index].count != nativeGroups[index].units.size) {
                    unchanged = false
                    break
                }
            }
            if (unchanged) return cachedSnapshot
        }

        val next = ArrayList<NativeHudGroup>(count)
        for (index in 0 until count) next.add(NativeHudGroup(index, nativeGroups[index].units.size))
        cachedLimit = visibleCount
        cachedSnapshot = next
        return next
    }

    fun select(index: Int, addToSelection: Boolean) {
        GameScope.launch {
            val engine = GameEngine.getInstance()
            val ui = engine.interfaceEngine
            val group = groups().getOrNull(index) ?: return@launch
            ui.cancelActiveAction()
            if (!addToSelection) ui.clearUnitSelection()
            group.selectUnits()
            group.selectFlash = 1f
        }
    }

    fun addSelection(index: Int, replace: Boolean) {
        GameScope.launch {
            val group = groups().getOrNull(index) ?: return@launch
            if (replace) group.clearUnits()
            group.addSelectedUnits()
            if (replace) group.replaceFlash = 1f else group.addGroupFlash = 1f
        }
    }

    fun clear(index: Int) {
        GameScope.launch {
            groups().getOrNull(index)?.clearUnits()
        }
    }

    fun quickSelect(category: Int) {
        GameScope.launch {
            val engine = GameEngine.getInstance()
            val ui = engine.interfaceEngine
            ui.clearUnitSelection()
            val movementTypes = when (category) {
                1 -> setOf(MovementType.NONE)
                2 -> setOf(MovementType.WATER, MovementType.OVER_CLIFF_WATER, MovementType.HOVER)
                3 -> setOf(MovementType.AIR)
                4 -> setOf(
                    MovementType.LAND,
                    MovementType.HOVER,
                    MovementType.OVER_CLIFF,
                    MovementType.OVER_CLIFF_WATER
                )

                else -> null
            }
            for (obj in GameObject.fastGameObjectList) {
                val unit = obj as? Unit ?: continue
                if (unit.dead || unit.transportedBy != null || unit.team !== engine.networkEngine.localTeam ||
                    unit.unitTypeInterface.isBuilder
                ) continue
                if (movementTypes?.contains(unit.unitTypeInterface.movementType)
                        ?: (unit.unitTypeInterface.movementType != MovementType.NONE)
                ) {
                    ui.selectUnit(unit)
                }
            }
        }
    }
}
