/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

internal data class TransportSpace(val id: Long, val freeSlots: Int, val x: Float, val y: Float)
internal data class TransportCandidate(
    val id: Long, val x: Float, val y: Float,
    /** Native-compatible carrier ids and the native slot cost for that specific carrier. */
    val slotCosts: Map<Long, Int>,
)

internal data class TransportAssignment(val passengerId: Long, val carrierId: Long, val slots: Int)

/** Constrained passengers first; best-fit capacity, then distance, with stable identity tie breaks. */
internal fun assignTransportSeats(
    spaces: List<TransportSpace>, passengers: List<TransportCandidate>,
): List<TransportAssignment> {
    val carriers = spaces.associateBy { it.id }
    val remaining = spaces.associate { it.id to it.freeSlots.coerceAtLeast(0) }.toMutableMap()
    return buildList {
        passengers.distinctBy { it.id }.sortedWith(
            compareBy<TransportCandidate> { passenger ->
                passenger.slotCosts.count { (id, cost) ->
                    cost > 0 && (remaining[id] ?: 0) >= cost
                }
            }.thenByDescending { it.slotCosts.values.maxOrNull() ?: 0 }.thenBy { it.id },
        ).forEach { passenger ->
            val carrier = passenger.slotCosts.entries.filter { (id, cost) ->
                cost > 0 && (remaining[id] ?: 0) >= cost && carriers.containsKey(id)
            }.minWithOrNull(compareBy<Map.Entry<Long, Int>> { remaining.getValue(it.key) - it.value }
                .thenBy {
                    val space = carriers.getValue(it.key)
                    val dx = passenger.x.toDouble() - space.x
                    val dy = passenger.y.toDouble() - space.y
                    dx * dx + dy * dy
                }.thenBy { it.key }) ?: return@forEach
            remaining[carrier.key] = remaining.getValue(carrier.key) - carrier.value
            add(TransportAssignment(passenger.id, carrier.key, carrier.value))
        }
    }
}
