/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent
import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.Unit

/** Health and shield deltas observed after a native damage call. */
data class UnitDamage(
    val unit: Unit,
    val attacker: Unit?,
    val projectile: Projectile?,
    val requestedDamage: Float,
    val healthDamage: Float,
    val shieldDamage: Float,
    val remainingDamage: Float,
)

data class UnitSpawnedEvent(val unit: Unit) : AbstractEvent()
data class UnitCreatedEvent(val unit: Unit) : AbstractEvent()
data class UnitConstructionCompletedEvent(val unit: Unit) : AbstractEvent()
data class UnitDamagedEvent(val damage: UnitDamage) : AbstractEvent()
data class UnitTeamChangedEvent(val unit: Unit) : AbstractEvent()
data class UnitUpgradedEvent(val unit: OrderableUnit) : AbstractEvent()
data class UnitDiedEvent(val unit: Unit) : AbstractEvent()
data class UnitRemovedEvent(val unit: Unit) : AbstractEvent()
