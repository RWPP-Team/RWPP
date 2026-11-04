/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.selection

internal const val BUILDER_GUARD_SETTLE_MS = 250L
internal const val BUILDER_GUARD_ORDER_INTERVAL_MS = 2_000L

/** Only native building waypoints are copied into this read-only client plan. */
internal data class BuilderQueueSite(val typeId: String, val x: Int, val y: Int, val tech: Int)
internal data class BuilderQueueMember(val id: Long, val plan: List<BuilderQueueSite>?)
internal data class BuilderGuardPlan(val leaderId: Long, val followerIds: List<Long>)

internal fun builderQueueIsSuffix(full: List<BuilderQueueSite>, suffix: List<BuilderQueueSite>): Boolean =
    suffix.isNotEmpty() && suffix.size <= full.size && full.takeLast(suffix.size) == suffix

/**
 * A common multi-building queue is the explicit group intent. The longest remaining queue wins
 * so followers cannot abandon an earlier unfinished site; ties use stable native unit identity.
 * An unrelated move, attack, guard or repair order is never replaced.
 */
internal fun planBuilderQueueProtection(members: List<BuilderQueueMember>): BuilderGuardPlan? {
    val leaders = members.filter { (it.plan?.size ?: 0) >= 2 }
        .sortedWith(compareByDescending<BuilderQueueMember> { it.plan!!.size }.thenBy { it.id })
    for (leader in leaders) {
        val followers = members.filter {
            it.id != leader.id && it.plan != null && builderQueueIsSuffix(leader.plan!!, it.plan)
        }.map { it.id }
        if (followers.isNotEmpty()) return BuilderGuardPlan(leader.id, followers)
    }
    return null
}

/** Mirrors native placement snapping for comparison only; no map scratch fields are modified. */
internal fun snappedBuilderSiteCoordinate(position: Float, origin: Float, tileSize: Int): Int =
    (((position - origin + 1f) / tileSize.coerceAtLeast(1)).toInt() * tileSize.coerceAtLeast(1) + origin).toInt()
