/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.minimap

internal data class MinimapSignal(val x: Float, val y: Float, val time: Long)

/** Bounded, short-lived world click markers shared by native orders and minimap input. */
internal object AeroMinimapOrders {
    private val active = ArrayDeque<MinimapSignal>()
    private var cachedSnapshot: List<MinimapSignal> = emptyList()
    private var snapshotDirty = false

    @Synchronized
    fun add(x: Float, y: Float, time: Long = System.currentTimeMillis()) {
        if (!x.isFinite() || !y.isFinite()) return
        if (active.size >= 8) active.removeFirst()
        active.addLast(MinimapSignal(x, y, time))
        snapshotDirty = true
    }

    @Synchronized
    fun snapshot(now: Long): List<MinimapSignal> {
        var expired = false
        while (active.firstOrNull()?.let { now - it.time > 700L || now < it.time } == true) {
            active.removeFirst()
            expired = true
        }
        if (expired) snapshotDirty = true
        if (snapshotDirty) {
            cachedSnapshot = active.toList()
            snapshotDirty = false
        }
        return cachedSnapshot
    }

    @Synchronized
    fun clear() {
        active.clear()
        cachedSnapshot = emptyList()
        snapshotDirty = false
    }
}
