/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.event.AbstractEvent

/** Fires after the native team wiped-out flag changes from false to true. */
data class TeamWipedOutEvent(val team: Team) : AbstractEvent()
