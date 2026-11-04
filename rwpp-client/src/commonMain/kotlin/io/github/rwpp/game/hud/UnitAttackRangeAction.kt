/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.CutSpecialAction
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** A local display action bound to the clicked unit, independent of ownership and simulation commands. */
internal object ShowSelectedUnitAttackRangeAction : CutSpecialAction("c_show_attack_range") {
    override fun getDisplayName(): String = readI18n("rwpp.settings.showAttackRange")
    override fun getDisplayDescription(): String = readI18n("rwpp.settings.showAttackRange")
    override fun isAvailableForSelection(): Boolean = true
    override fun onButtonClicked(unit: GameUnit, alternate: Boolean): Boolean {
        if (unit !is OrderableUnit || unit.maxAttackRange <= 0f) return false
        appKoin.get<EntityRangeUnitComp>().toggleAttackRange(unit)
        return true
    }

    override fun compareTo(other: Any?): Int = 0
}
