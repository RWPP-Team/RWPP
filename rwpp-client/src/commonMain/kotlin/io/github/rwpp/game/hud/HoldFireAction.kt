/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.AttackMode
import com.corrodinggames.rts.game.units.EditorUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.AttackModeToggle
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.mode.DisableHoldFireMode
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import com.corrodinggames.rts.game.units.Unit as GameUnit

internal enum class HoldFireState(val statusKey: String?) {
    OFF(null),
    MIXED("rwpp.hud.holdFire.mixed"),
    ON("rwpp.hud.holdFire.active"),
}

internal object HoldFireAction : AttackModeToggle() {
    private fun isEnabled(): Boolean = appKoin.get<Settings>().holdFireEnabled

    private fun roomAllowsHoldFire(): Boolean =
        appKoin.get<Game>().gameRoom.gameModes.activeModes.none { it.id == DisableHoldFireMode.ID }

    fun isAvailable(engine: GameEngine, selection: Iterable<*>): Boolean =
        isEnabled() && when (state(engine, selection)) {
            HoldFireState.ON -> true
            HoldFireState.OFF, HoldFireState.MIXED -> roomAllowsHoldFire()
            null -> false
        }

    fun canApply(engine: GameEngine, unit: OrderableUnit): Boolean =
        unit !is EditorUnit && !unit.dead && !unit.removed &&
            unit.canAttack() && engine.interfaceEngine.canPlayerControlUnit(unit)

    fun state(engine: GameEngine, selection: Iterable<*>): HoldFireState? {
        var held = false
        var free = false
        for (unit in selection) {
            if (unit !is OrderableUnit || !canApply(engine, unit)) continue
            if (unit.attackMode == AttackMode.holdFire) held = true else free = true
            if (held && free) return HoldFireState.MIXED
        }
        return when {
            held -> HoldFireState.ON
            free -> HoldFireState.OFF
            else -> null
        }
    }

    fun buttonLabel(state: HoldFireState?): String =
        readI18n(if (state == HoldFireState.ON) "rwpp.hud.holdFire.resume" else "rwpp.hud.holdFire.enable")

    override fun getDisplayName(): String {
        val engine = GameEngine.getInstance()
        return buttonLabel(state(engine, engine.interfaceEngine.selectedUnits))
    }

    override fun getDisplayDescription(): String = readI18n("rwpp.hud.holdFire.description")

    override fun getUnitType(): UnitTypeInterface? = null

    override fun compareTo(other: Any?): Int = 0

    override fun performDirectAction(unit: GameUnit) {
        val engine = GameEngine.getInstance()
        toggle(engine, engine.interfaceEngine.selectedUnits)
    }

    fun toggle(engine: GameEngine, selection: Iterable<*>) {
        if (!isEnabled() || engine.replayEngine.isPlayingReplay || isAeroLiveObserver(engine)) return
        val targets = selection.filterIsInstance<OrderableUnit>().filter { canApply(engine, it) }
        if (targets.isEmpty()) return
        val mode = if (targets.all { it.attackMode == AttackMode.holdFire }) AttackMode.onlyInRange else AttackMode.holdFire
        if (mode == AttackMode.holdFire && !roomAllowsHoldFire()) return
        val command = engine.interfaceEngine.playerCommand
        for (unit in targets) if (unit.attackMode != mode) command.addUnit(unit)
        command.setAttackMode(mode)
    }
}
