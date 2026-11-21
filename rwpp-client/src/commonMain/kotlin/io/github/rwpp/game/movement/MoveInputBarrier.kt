/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.game.movement

import com.corrodinggames.rts.game.units.Waypoint
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.network.OutputNetStream

/** Only properties preserved by native command write/read identify a returning local order. */
internal data class MoveInputSignature(
    val waypoint: List<Byte>?, val queued: Boolean, val clear: Boolean,
    val finish: Boolean, val remove: Boolean, val cancelDuplicate: Boolean,
    val system: Boolean, val systemId: Int,
) {
    companion object {
        fun of(command: CommandControllerCommand) = MoveInputSignature(
            command.waypoint?.let(::orderBytes), command.queueOrder, command.clearWaypointsFirst,
            command.finishNonBuildWaypoints, command.removeFromQueue, command.cancelDuplicateWaypoint,
            command.hasSystemAction, command.systemActionId,
        )

        private fun orderBytes(order: Waypoint): List<Byte> =
            OutputNetStream().also(order::write).toByteArray().toList()
    }

    fun matchesOrder(order: Waypoint?): Boolean = waypoint != null && order != null &&
        waypoint == orderBytes(order)
}

/**
 * Native networking has no command UUID. Keep every outgoing order, including automatic ones, in
 * its unit's FIFO so one native application can acknowledge only one matching outgoing order.
 */
internal class MoveInputBarrier {
    class Receipt internal constructor(val signature: MoveInputSignature) {
        var received = false
            internal set
        var applied = false
            internal set
    }

    private val pending = ArrayDeque<Receipt>()
    var poisoned: Boolean = false
        private set
    val empty: Boolean get() = pending.isEmpty()

    fun register(command: CommandControllerCommand): Receipt {
        val receipt = Receipt(MoveInputSignature.of(command))
        if (pending.size >= MAX_PENDING) poisoned = true
        if (poisoned) {
            // Keep this unit native until its world changes. Clearing the FIFO could misidentify an
            // old packet as a new, identical input, so no later registration can restart this gate.
            receipt.received = true
        } else pending.addLast(receipt)
        return receipt
    }

    fun discard(receipt: Receipt) { pending.remove(receipt) }

    /** Mismatched or rejected applications never release a later, possibly identical user input. */
    fun acknowledge(command: CommandControllerCommand, appliedOrder: Waypoint?): Receipt? {
        if (poisoned) return null
        val first = pending.firstOrNull() ?: return null
        if (first.signature != MoveInputSignature.of(command)) {
            poisoned = true
            return null
        }
        pending.removeFirst()
        first.received = true
        first.applied = first.signature.waypoint == null || first.signature.matchesOrder(appliedOrder)
        return first
    }

    private companion object { const val MAX_PENDING = 128 }
}
