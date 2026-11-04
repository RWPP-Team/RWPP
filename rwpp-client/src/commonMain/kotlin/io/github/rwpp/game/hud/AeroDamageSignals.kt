/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import androidx.compose.ui.geometry.Offset
import io.github.rwpp.graphics.AeroDamageNumber

/** Bounded, game-time animation state. Nearby hits coalesce instead of creating text per bullet. */
internal class AeroDamageTimeline {
    private val entries = ArrayList<AeroDamageNumber>()
    private var nextLane = 0

    fun hit(id: Int, position: Offset, amount: Float, now: Long) {
        if (!amount.isFinite() || amount <= 0f || !position.x.isFinite() || !position.y.isFinite()) return
        entries.removeAll { now - it.bornAt >= 900 || now < it.bornAt }
        val index = entries.indexOfLast { it.unitId == id && now - it.bornAt in 0..90 }
        if (index >= 0) entries[index] = entries[index].copy(amount = entries[index].amount + amount)
        else {
            if (entries.size >= 128) entries.removeAt(0)
            entries += AeroDamageNumber(id, position, amount, now, nextLane++ % 3 - 1)
        }
    }

    fun frame(now: Long): List<AeroDamageNumber> {
        entries.removeAll { now - it.bornAt >= 900 || now < it.bornAt }
        return entries.map { it.copy(age = ((now - it.bornAt) / 900f).coerceIn(0f, 1f)) }
    }

    fun clear() {
        entries.clear()
        nextLane = 0
    }
}

/** The simulation enqueues visible HP/shield loss; Compose consumes immutable snapshots. */
internal object AeroDamageSignals {
    @Volatile
    var enabled = false
    private val timeline = AeroDamageTimeline()

    @Synchronized
    fun hit(id: Int, position: Offset, amount: Float, now: Long) {
        if (enabled) timeline.hit(id, position, amount, now)
    }

    @Synchronized
    fun frame(now: Long) = timeline.frame(now)

    @Synchronized
    fun clear() {
        timeline.clear()
    }
}
