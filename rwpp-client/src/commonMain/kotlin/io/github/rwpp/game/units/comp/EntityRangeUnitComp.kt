/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.units.comp

import com.corrodinggames.rts.game.units.Unit
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.DisconnectEvent
import io.github.rwpp.utils.argb
import org.koin.core.annotation.Single

@Single
class EntityRangeUnitComp {
    private val explicitRanges = java.util.WeakHashMap<Unit, Boolean>()
    fun toggleAttackRange(unit: Unit): Boolean = synchronized(explicitRanges) {
        if (explicitRanges.remove(unit) == null) {
            explicitRanges[unit] = true; true
        } else false
    }

    fun isAttackRangeShown(unit: Unit): Boolean = synchronized(explicitRanges) { explicitRanges[unit] == true }
    fun hasExplicitRanges(): Boolean = synchronized(explicitRanges) {
        explicitRanges.keys.removeAll { it.dead || it.removed }
        explicitRanges.isNotEmpty()
    }

    fun clearAttackRanges() = synchronized(explicitRanges) { explicitRanges.clear() }

    init {
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways { clearAttackRanges() }
    }

    companion object {
        private val argb1 = argb(200, 183, 44, 44)
        private val argb2 = argb(200, 0, 150, 0)
        private val healthBarEnemyOutline = argb(120, 255, 60, 60)
        private val healthBarAllyOutline = argb(120, 0, 230, 0)
        private val healthBarChange = argb(100, 232, 208, 26)

        internal fun isNativeHealthBarPaintColor(color: Int): Boolean =
            isNativeHealthFillPaintColor(color) || color == healthBarEnemyOutline ||
                    color == healthBarAllyOutline || color == healthBarChange

        private fun isNativeHealthFillPaintColor(color: Int): Boolean = color == argb1 || color == argb2

    }
}
