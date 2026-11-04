/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import io.github.rwpp.api.event.AbstractEvent

/** A native command submission before it enters the command queue. */
class CommandQueueEvent(val command: CommandControllerCommand) : GameEvent() {
    override val propagateListenerFailures: Boolean
        get() = true
}

/** Native pathfinding completed for a command. */
data class CommandPathsReadyEvent(val command: CommandControllerCommand) : AbstractEvent()
