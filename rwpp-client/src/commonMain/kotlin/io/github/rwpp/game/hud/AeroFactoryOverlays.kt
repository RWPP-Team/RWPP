/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.corrodinggames.rts.game.units.buildings.BuildQueueItem
import com.corrodinggames.rts.game.units.buildings.FactoryBuildQueue
import com.corrodinggames.rts.game.units.buildings.FactoryInterface
import io.github.rwpp.game.nativeaccess.NativeFactoryQueueAccess
import io.github.rwpp.graphics.ActionImages
import io.github.rwpp.graphics.AeroProductionVisual
import io.github.rwpp.graphics.HudIconKey
import io.github.rwpp.graphics.hudIconRequest
import kotlin.math.ceil
import com.corrodinggames.rts.game.units.Unit as GameUnit


/** Native factories and custom factories both keep their queue in a FactoryBuildQueue field. */
internal object NativeFactoryOverlays {
    fun queue(unit: GameUnit): FactoryBuildQueue? = (unit as? NativeFactoryQueueAccess)?.rwppBuildQueue()
    fun rallyPoint(unit: GameUnit): Offset? = queue(unit)?.rallyPoint?.let {
        if (it.x.isFinite() && it.y.isFinite()) Offset(it.x, it.y) else null
    }
}

/** Native queue speed is progress per simulation tick; the game runs 60 ticks per second. */
internal fun productionRemainingSeconds(progress: Float, buildSpeed: Float, factorySpeed: Float): Int? {
    val perSecond = buildSpeed * factorySpeed * 60f
    if (!progress.isFinite() || !perSecond.isFinite() || perSecond <= 0f) return null
    return ceil((1f - progress.coerceIn(0f, 1f)) / perSecond).toDouble()
        .coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
}

/** Keeps expensive preview/title work outside the per-frame path and observes true queue stalls. */
internal class AeroProductionSampler {
    private data class State(
        val item: BuildQueueItem, val teamColor: Int, val name: String,
        var icon: ImageBitmap?, var iconKey: HudIconKey?, var progress: Float, var changedAt: Long
    )

    private val states = HashMap<GameUnit, State>()
    private val live = HashSet<GameUnit>()
    fun beginFrame() {
        live.clear()
    }

    fun endFrame() {
        states.keys.retainAll(live)
    }

    fun clear() {
        states.clear(); live.clear()
    }

    fun sample(unit: GameUnit, now: Long, gamePaused: Boolean = false): AeroProductionVisual? {
        val factory = unit as? FactoryInterface ?: return null
        val item = factory.currentQueuedItem ?: factory.queuedItems.firstOrNull() as? BuildQueueItem ?: return null
        val progress = (NativeFactoryOverlays.queue(unit)?.buildProgress ?: item.progress).coerceIn(0f, 1f)
        live += unit
        var state = states[unit]
        if (state == null || state.item !== item || state.teamColor != unit.team.color || now < state.changedAt) {
            val action = unit.getSpecialAction(item.actionId)
            state = State(
                item, unit.team.color,
                item.unitType?.displayName ?: action?.getDisplayNameForUnit(unit).orEmpty(),
                null, null, progress, now
            )
            states[unit] = state
        }
        // Keep the completed image with its factory instead of replacing it with null if the
        // shared cache evicts it while many different factories are on screen.
        unit.getSpecialAction(item.actionId)?.let { action ->
            val request = action.hudIconRequest(unit)
            if (state.icon == null || state.iconKey != request.key) {
                ActionImages.request(request)?.let { image -> state.icon = image; state.iconKey = request.key }
            }
        }
        if (progress != state.progress) {
            state.progress = progress; state.changedAt = now
        }
        val blockedExit = progress >= 1f && item.unitProduction && factory.isOverTransportCapacity
        val paused = gamePaused || !unit.isBuiltAndNotTransported || unit.nanoFactorySpeed <= 0f
        val waiting = blockedExit || now - state.changedAt >= 1000L
        return AeroProductionVisual(
            Offset(unit.x, unit.y - unit.height), unit.radius, state.name,
            productionRemainingSeconds(progress, item.buildTime, unit.nanoFactorySpeed),
            factory.getQueuedCountOf(item.actionId, false).coerceAtLeast(item.buildCount).coerceAtLeast(1),
            progress, paused, Color(unit.team.color), state.icon, waiting
        )
    }
}
