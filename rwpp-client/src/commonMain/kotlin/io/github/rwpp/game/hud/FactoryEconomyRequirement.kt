/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.IndirectedAction
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.game.units.actions.SpecialActionCategory
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.gui.BattleInterface
import kotlin.math.ceil
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Factor applied to price / build time, as specified for the per-minute economy estimate. */
internal const val FACTORY_ECONOMY_FACTOR = 0.66

/**
 * Minimum economy needed to keep every factory able to build a unit producing it continuously:
 * `price / buildSeconds * 0.66 * factories`. Returns null when any input is unusable.
 */
internal fun factoryEconomyRequirement(price: Double, buildSeconds: Double, factories: Int): Int? {
    if (!price.isFinite() || price <= 0.0 || !buildSeconds.isFinite() || buildSeconds <= 0.0 || factories <= 0) {
        return null
    }
    val value = price / buildSeconds * FACTORY_ECONOMY_FACTOR * factories
    return if (value.isFinite()) ceil(value).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null
}

private val clockTime = Regex("""(\d+):(\d{1,2})(?::(\d{1,2}))?""")
private val minutesPart = Regex("""(\d+(?:[.,]\d+)?)\s*(?:m(?:in)?\b|分)""", RegexOption.IGNORE_CASE)
private val secondsPart = Regex("""(\d+(?:[.,]\d+)?)\s*(?:s(?:ec)?\b|秒)""", RegexOption.IGNORE_CASE)
private val anyNumber = Regex("""\d+(?:[.,]\d+)?""")

/**
 * Parses the build time the native tooltip shows ([BattleInterface.getSpecialActionQueueTimeText]).
 * Accepts `m:ss`, `h:mm:ss`, `1m 30s`, `45s`, `45秒`, and falls back to a bare number of seconds.
 */
internal fun parseQueueTimeSeconds(text: String?): Double? {
    if (text.isNullOrBlank()) return null
    clockTime.find(text)?.let { match ->
        val (a, b, c) = match.destructured
        val seconds = if (c.isNotEmpty()) a.toDouble() * 3600 + b.toDouble() * 60 + c.toDouble()
        else a.toDouble() * 60 + b.toDouble()
        return seconds.takeIf { it > 0.0 }
    }
    fun Regex.amount() = find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    val minutes = minutesPart.amount()
    val seconds = secondsPart.amount()
    if (minutes != null || seconds != null) {
        return ((minutes ?: 0.0) * 60 + (seconds ?: 0.0)).takeIf { it > 0.0 }
    }
    return anyNumber.find(text)?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 }
}

/** [perFactory] is the economy one factory needs; [perMinute] is the total for all [factories]. */
internal data class FactoryEconomy(val perFactory: Int, val perMinute: Int, val factories: Int)

/**
 * Samples, for the factory currently selected, how much economy each unit button needs. Only
 * buildings count as producers, so engineers and other mobile builders are ignored both as the
 * selection and as additional factories. A factory counts only when the native action is
 * available to it, which keeps T2-only units from counting T1 factories of the same type.
 */
internal class FactoryEconomySampler {
    private var sampledAt = Long.MIN_VALUE
    private var sampledPrimary: GameUnit? = null
    private val results = HashMap<Any, FactoryEconomy?>()
    private val ownBuildings = ArrayList<OrderableUnit>()

    fun beginFrame(primary: GameUnit?, nowNanos: Long) {
        if (primary === sampledPrimary && nowNanos - sampledAt in 0..<250_000_000L) return
        sampledAt = nowNanos
        sampledPrimary = primary
        results.clear()
        ownBuildings.clear()
        if (primary !is OrderableUnit || !runCatching { primary.isBuilding }.getOrDefault(false)) return
        val team = primary.team ?: return
        val ui = GameEngine.getInstance().interfaceEngine
        runCatching {
            for (native in GameUnit.unitList) {
                val unit = native as? OrderableUnit ?: continue
                if (unit.dead || unit.team !== team || !unit.isBuilding || !unit.isBuiltAndNotTransported) continue
                if (!runCatching { ui.canPlayerControlUnit(unit) }.getOrDefault(false)) continue
                ownBuildings += unit
            }
        }
    }

    /** Null for anything that is not unit production from a building. */
    fun sample(action: SpecialAction): FactoryEconomy? {
        if (ownBuildings.isEmpty() || action is IndirectedAction) return null
        if (action.actionCategory != SpecialActionCategory.queueUnit || action.unitType == null) return null
        val id = action.storedActionId
        if (results.containsKey(id)) return results[id]
        val value = runCatching { compute(action) }.getOrNull()
        results[id] = value
        return value
    }

    private fun compute(action: SpecialAction): FactoryEconomy? {
        val id = action.storedActionId
        val price = action.customPrice?.credits?.toDouble() ?: action.priceValue.toDouble()
        val seconds = parseQueueTimeSeconds(BattleInterface.getSpecialActionQueueTimeText(action, false))
            ?: return null
        var factories = 0
        for (unit in ownBuildings) {
            val owned = runCatching { unit.getSpecialAction(id) }.getOrNull() ?: continue
            if (runCatching { owned.isAvailableForUnit(unit) }.getOrDefault(false)) factories++
        }
        val perFactory = factoryEconomyRequirement(price, seconds, 1) ?: return null
        val perMinute = factoryEconomyRequirement(price, seconds, factories) ?: return null
        return FactoryEconomy(perFactory, perMinute, factories)
    }
}
