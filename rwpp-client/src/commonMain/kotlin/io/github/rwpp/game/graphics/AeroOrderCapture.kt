/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.graphics

import com.corrodinggames.rts.gameFramework.effects.EffectObject
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.minimap.AeroMinimapOrders

/** Coordinates stay in native world space until the UI layer maps them to its canvas. */
internal data class AeroOrderClick(val x: Float, val y: Float, val attack: Boolean, val time: Long)

internal object AeroOrderClicks {
    private val events = ArrayDeque<AeroOrderClick>()
    private var cachedFrame: List<AeroOrderClick> = emptyList()
    private var frameDirty = false

    @Synchronized
    fun add(x: Float, y: Float, attack: Boolean, now: Long = System.currentTimeMillis()) {
        if (!x.isFinite() || !y.isFinite()) return
        if (events.size >= 16) events.removeFirst()
        events.addLast(AeroOrderClick(x, y, attack, now))
        frameDirty = true
    }

    @Synchronized
    fun frame(now: Long): List<AeroOrderClick> {
        var expired = false
        while (events.firstOrNull()?.let { now - it.time > 700 || now < it.time } == true) {
            events.removeFirst()
            expired = true
        }
        if (expired) frameDirty = true
        if (frameDirty) {
            cachedFrame = events.toList()
            frameDirty = false
        }
        return cachedFrame
    }

    @Synchronized
    fun clear() {
        events.clear()
        cachedFrame = emptyList()
        frameDirty = false
    }
}

/** Called only for native commands, not for the per-frame particle spawn path. */
internal object AeroOrderCapture {
    private val captured = ThreadLocal<MutableList<EffectObject>?>()

    fun record(effect: EffectObject?) {
        if (effect != null) captured.get()?.let { if (it.size < 16) it.add(effect) }
    }

    fun run(x: Float, y: Float, attack: Boolean, command: () -> kotlin.Unit) {
        val clickEffectsEnabled = runCatching { appKoin.get<Settings>().aeroOrderClickEffects }.getOrDefault(false)
        if (!clickEffectsEnabled) {
            command()
            return
        }
        val previous = captured.get()
        val effects = ArrayList<EffectObject>()
        captured.set(effects)
        try {
            command()
            if (effects.isNotEmpty()) {
                // Keep native return values intact: some commands inspect the first effect while
                // creating minimap feedback. Suppress artwork only after native setup completes.
                effects.forEach { it.alpha = 0f; it.life = 0f }
                AeroOrderClicks.add(x, y, attack)
                AeroMinimapOrders.add(x, y)
            }
        } finally {
            captured.set(previous)
        }
    }
}
