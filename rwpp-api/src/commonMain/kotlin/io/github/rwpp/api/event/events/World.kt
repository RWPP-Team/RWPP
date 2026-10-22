/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent
import com.corrodinggames.rts.gameFramework.trigger.MapTrigger

sealed class WorldEvent : AbstractEvent()

class TriggerActivateEvent(val id: String, val trigger: MapTrigger? = null) : WorldEvent()

class LocalPlayerUnitDamagedEvent : WorldEvent()

class LocalPlayerBaseDamagedEvent : WorldEvent()

class LocalPlayerUnitCreatedEvent : WorldEvent()

class LocalPlayerBuildingConstructedEvent : WorldEvent()

class LocalPlayerUpgradedCompletedEvent : WorldEvent()
