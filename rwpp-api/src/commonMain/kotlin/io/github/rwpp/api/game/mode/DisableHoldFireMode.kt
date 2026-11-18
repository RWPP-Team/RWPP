/*
 * Copyright 2023-2026 RWPP contributors
 * This file is governed by the RWPP-Team Shield license in LICENSE.md.
 */

package io.github.rwpp.api.game.mode

import com.corrodinggames.rts.game.units.AttackMode
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.CommandQueueEvent
import io.github.rwpp.api.event.events.SimulationBeforeTickEvent

/** Reject new hold-fire orders where the host accepts native commands. */
object DisableHoldFireMode : GameMode {
    const val ID = "rwpp:disable-hold-fire"
    override val id: String = ID
    override val displayName: String = "Disable hold fire"
    override val requiresHostAuthority: Boolean = true

    override fun onEnable(context: GameModeContext) {
        GlobalEventChannel.filter(CommandQueueEvent::class).subscribeAlways(context.eventOwner) { event ->
            if (event.command.attackMode == AttackMode.holdFire && context.session.hasNativeAuthority()) {
                event.cancel()
            }
        }
        GlobalEventChannel.filter(SimulationBeforeTickEvent::class).subscribeAlways(context.eventOwner) { event ->
            val engine = event.engine
            if (engine.networkEngine.networked || !context.session.hasNativeAuthority(engine)) return@subscribeAlways
            engine.commandController.localPendingCommands.removeAll {
                (it as? CommandControllerCommand)?.attackMode == AttackMode.holdFire
            }
        }
    }
}
