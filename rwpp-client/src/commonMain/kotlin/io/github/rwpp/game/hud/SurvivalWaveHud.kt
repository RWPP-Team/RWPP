/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.trigger.TriggerEngine
import io.github.rwpp.api.logging.logger
import io.github.rwpp.graphics.ActionImages
import kotlin.math.ceil
import kotlin.math.abs

internal data class SurvivalWaveUnit(val name: String, val count: Int, val icon: ImageBitmap?)

internal data class SurvivalWaveHud(
    val completed: Int,
    val currentWaveSummary: String?,
    val defeated: Boolean,
    val hasNext: Boolean,
    val seconds: Int?,
    val name: String?,
    val units: List<SurvivalWaveUnit>,
)

internal enum class SurvivalSpawnStatus { CANDIDATE, PREDICTED, CONFIRMED }

internal data class SurvivalSpawnMarker(val position: Offset, val status: SurvivalSpawnStatus)

internal object SurvivalWavePresentation {
    @Volatile var replacesNativeText = false
}

/** Reads the native mission under gameUpdateLock. Wave generation uses its preview-only path. */
internal class SurvivalWaveSampler {
    private data class PreviewKey(val mission: TriggerEngine, val wave: Int, val classic: Boolean, val difficulty: Int)

    private var previewKey: PreviewKey? = null
    private var composition = emptyMap<UnitTypeInterface, Int>()
    private var failed = false
    private val native by lazy { NativeWaves() }

    fun sample(engine: GameEngine): SurvivalWaveHud? {
        val mission = engine.triggerEngine?.takeIf { it.survivalMap } ?: return null
        if (failed) return null
        return try {
            val defeated = native.defeat.getBoolean(mission)
            val wave = if (mission.mapWavesLoaded) mission.getCurrentWave() else null
            val hasNext = !defeated && (!mission.mapWavesLoaded || wave != null)
            val seconds = when {
                !hasNext -> null
                wave == null -> ceil(native.timer.getFloat(mission) / 60f).toInt().coerceAtLeast(0)
                wave.noTimer -> null
                else -> (wave.e - engine.gameTimeMillis / 1000).coerceAtLeast(0)
            }
            val key = PreviewKey(mission, mission.currentWaveIndex, mission.survivalWavesClassic, mission.aiDifficulty)
            if (hasNext && previewKey != key) {
                val groups = if (wave != null) wave.unitLists else {
                    val generate = if (mission.survivalWavesClassic) native.classic else native.procedural
                    listOf(generate.invoke(mission, false))
                }
                composition = native.countUnits(groups)
                previewKey = key
            }
            val team = Team.getTeam(1) ?: Team.grayTeam
            val units = if (hasNext) composition.map { (type, count) ->
                SurvivalWaveUnit(type.displayName, count, ActionImages.loadUnitType(type, team, visible = true))
            } else emptyList()
            val currentSummary = if (!mission.mapWavesLoaded && native.bannerTimer.getFloat(mission) > 0f) {
                native.bannerText.get(mission) as? String
            } else null
            SurvivalWaveHud(
                mission.currentWaveIndex, currentSummary, defeated, hasNext,
                seconds, wave?.name?.takeIf { it.isNotBlank() }, units,
            )
        } catch (failure: Exception) {
            failed = true
            logger().warn("Could not read native survival waves", failure)
            null
        }
    }

    fun spawnMarkers(engine: GameEngine): List<SurvivalSpawnMarker> {
        val mission = engine.triggerEngine ?: return emptyList()
        if (failed) return emptyList()
        val wave = if (mission.mapWavesLoaded) mission.getCurrentWave() ?: return emptyList() else null
        if (mission.mapWavesLoaded && native.spawnInitialized.getBoolean(mission)) {
            val point = native.spawnPoint.get(mission) as PointF
            return listOf(SurvivalSpawnMarker(Offset(point.x, point.y), SurvivalSpawnStatus.CONFIRMED))
        }
        val points = native.attackPoints.get(mission) as List<*>
        // The native selector passes size - 1 as an exclusive upper bound when there is more than one point.
        val selectable = points.take((points.size - 1).coerceAtLeast(1)).filterIsInstance<PointF>()
        if (selectable.size == 1) {
            val point = selectable.single()
            return listOf(SurvivalSpawnMarker(Offset(point.x, point.y), SurvivalSpawnStatus.CONFIRMED))
        }
        if (selectable.isEmpty()) return emptyList()

        // Only the fixed-step engine paths know how far the clock will advance on each future tick.
        if (engine is RWGameEngine && (engine.networkEngine.networked || engine.replayEngine.isRecordingOrPlaying)) {
            val step = engine.networkEngine.currentStepRate * engine.gameSpeedMultiplier
            val timer = native.timer.getFloat(mission)
            val ticks = ticksUntilSurvivalSpawn(step, timer, engine.gameTimeMillis, wave?.let { it.e * 1000 })
            if (ticks != null) {
                val waveSeed = mission.currentWaveIndex + if (mission.mapWavesLoaded) 0 else 1
                val index = survivalSpawnIndex(selectable.size, engine.randomSeed, waveSeed, engine.simulationTick + ticks)
                val point = selectable[index]
                return listOf(SurvivalSpawnMarker(Offset(point.x, point.y), SurvivalSpawnStatus.PREDICTED))
            }
        }
        return selectable.map { SurvivalSpawnMarker(Offset(it.x, it.y), SurvivalSpawnStatus.CANDIDATE) }
    }
}

internal fun ticksUntilSurvivalSpawn(step: Float, remaining: Float, gameTime: Int, scriptTime: Int?): Int? {
    if (!step.isFinite() || step <= 0f) return null
    var timer = remaining
    var time = gameTime
    // Match the engine's float subtraction and integer time truncation at every step.
    // Long scripted delays can be previewed once they enter this bounded lookahead.
    for (ticks in 1..7200) {
        if (scriptTime == null) {
            timer = CommonUtils.applyDeadzone(timer, step)
            if (timer == 0f) return ticks
        } else {
            val next = (time + step * 16.666666f).toInt()
            if (next > scriptTime) return ticks
            if (next == time) return null
            time = next
        }
    }
    return null
}

/** CommonUtils.deterministicRandomInt(0, bound, wave), evaluated at the future simulation tick. */
internal fun survivalSpawnIndex(bound: Int, seed: Int, wave: Int, tick: Int): Int {
    var mixed = seed
    mixed += wave * 133333333 * bound
    mixed += wave * 13131313
    mixed += wave * tick * 13131313
    mixed += tick * 1313131313 + tick % 10
    return abs(mixed % bound)
}

/** The native generators return a package-private type, so read that result via the named game ABI. */
private class NativeWaves {
    private val trigger = TriggerEngine::class.java
    val procedural = trigger.getMethod("generateWaveUnits", Boolean::class.javaPrimitiveType)
    val classic = trigger.getMethod("generateClassicWaveUnits", Boolean::class.javaPrimitiveType)
    val timer = trigger.waveField("nextWaveTimer")
    val bannerTimer = trigger.waveField("waveTextTimer")
    val bannerText = trigger.waveField("currentWaveUnitsText")
    val defeat = trigger.waveField("defeat")
    val attackPoints = trigger.waveField("attackPoints")
    val spawnPoint = trigger.waveField("waveSpawnPoint")
    val spawnInitialized = trigger.waveField("spawnPointInitialized")
    private val unitEntries = procedural.returnType.waveField("entries")
    private val entryClass = Class.forName("${trigger.name.substringBeforeLast('.')}.WaveUnitListEntry", false, trigger.classLoader)
    private val unitType = entryClass.waveField("unitType")
    private val count = entryClass.waveField("count")

    fun countUnits(groups: List<*>): Map<UnitTypeInterface, Int> = buildMap {
        for (group in groups) {
            for (entry in unitEntries.get(group) as Iterable<*>) {
                val type = unitType.get(entry) as? UnitTypeInterface ?: continue
                val amount = count.getInt(entry)
                if (amount > 0) put(type, (get(type) ?: 0) + amount)
            }
        }
    }

    private fun Class<*>.waveField(name: String) = getDeclaredField(name).apply { isAccessible = true }
}
