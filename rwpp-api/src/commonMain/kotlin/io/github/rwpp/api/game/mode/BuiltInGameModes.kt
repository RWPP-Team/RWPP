/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.mode

import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.game.units.WaypointType
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.CommandQueueEvent
import io.github.rwpp.api.event.events.SimulationBeforeTickEvent
import io.github.rwpp.api.map.XMLMap

/** Native skirmish rules. It preserves the selected map directory and writes the native rules type. */
class SkirmishGameMode : GameMode {
    override val id: String = ID
    override val displayName: String = "Skirmish"
    override val kind: GameModeKind = GameModeKind.BASE
    override val requiresHostAuthority: Boolean = true
    override val order: Int = Int.MIN_VALUE
    override val nativeMapInfoType: String = "skirmish"
    override val transformsMap: Boolean = true

    override fun transformMap(map: XMLMap, context: GameModeContext) {
        map.addMapInfo(nativeMapInfoType)
        map.mapInfoObject?.setProperty("type", nativeMapInfoType)
    }

    companion object {
        const val ID = "rwpp:skirmish"
    }
}

/** Native survival rules; validates the map fields that TriggerEngine assumes are present. */
class SurvivalGameMode : GameMode {
    override val id: String = ID
    override val displayName: String = "Survival"
    override val kind: GameModeKind = GameModeKind.BASE
    override val requiresHostAuthority: Boolean = true
    override val order: Int = Int.MIN_VALUE
    override val nativeMapInfoType: String = "survival"
    override val transformsMap: Boolean = true

    override fun validateMap(map: XMLMap, context: GameModeContext) {
        val info = map.mapInfoObject
            ?: throw GameModeException("Survival mode requires a map_info object in the map's triggers layer.")
        val winCondition = info.getProperty("winCondition")?.trim().orEmpty()
        if (WIN_CONDITIONS.none { it.equals(winCondition, ignoreCase = true) }) {
            throw GameModeException(
                "Survival mode requires a supported map_info.winCondition; got '${winCondition.ifEmpty { "<missing>" }}'.",
            )
        }
        val attackPoints = map.objects.count {
            it.name.equals("attack_point", ignoreCase = true) && it.x != null && it.y != null
        }
        if (attackPoints == 0) {
            throw GameModeException("Survival mode requires at least one positioned attack_point object.")
        }
        val waveScript = info.getProperty("survivalWaves")
        if (waveScript != null && waveScript.lineSequence().filter(String::isNotBlank).any { !isWaveLine(it) }) {
            throw GameModeException(
                "map_info.survivalWaves contains an invalid wave line; check its native +/! marker, options, time, and unit counts.",
            )
        }
        if (waveScript != null && waveScript.lineSequence().none(String::isNotBlank)) {
            throw GameModeException(
                "map_info.survivalWaves is present but empty; the native game would finish immediately.",
            )
        }
    }

    override fun transformMap(map: XMLMap, context: GameModeContext) {
        map.addMapInfo(nativeMapInfoType)
        map.mapInfoObject?.setProperty("type", nativeMapInfoType)
    }

    private fun isWaveLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith('!')) return hasSupportedOptions(trimmed.substring(1))
        val match = PLUS_WAVE_LINE.matchEntire(trimmed) ?: return false
        if (!hasSupportedOptions(match.groups[3]?.value)) return false
        val unitSection = match.groups[4]?.value?.removePrefix("-")?.trim() ?: return true
        if (unitSection.isEmpty()) return false
        return unitSection.split(',').all { unitEntry ->
            val entry = unitEntry.trim()
            val countEnd = entry.indexOf(' ')
            countEnd > 0 &&
                entry.substring(0, countEnd).trim().toIntOrNull() != null &&
                entry.substring(countEnd + 1).trim().isNotEmpty()
        }
    }

    private fun hasSupportedOptions(value: String?): Boolean = value == null || value.split(',').all { option ->
        WAVE_OPTIONS.any { it.equals(option.trim(), ignoreCase = true) }
    }

    companion object {
        const val ID = "rwpp:survival"
        private val PLUS_WAVE_LINE = Regex("\\+(\\d+(?::\\d+)?)([^\\[-]*)(?:\\[(.*?)\\])? *(-.*)?")
        private val WAVE_OPTIONS = setOf("", "lockSpawn", "unlockSpawn", "noTimer", "paused", "win")
        private val WIN_CONDITIONS = setOf(
            "none",
            "allUnitsAndBuildings",
            "allBuildings",
            "mainBuildings",
            "mainBuilings",
            "commandCenter",
            "requiredObjectives",
        )
    }
}

/**
 * A composable unit ban. It cancels native build commands at the command queue boundary and owns
 * that listener only for as long as this mode remains active.
 */
class BanUnitsMode(
    units: Set<String>,
    override val id: String = ID,
    override val displayName: String = "Ban units",
    override val order: Int = 0,
    override val requires: Set<String> = emptySet(),
    override val conflictsWith: Set<String> = emptySet(),
) : GameMode {
    override val requiresHostAuthority: Boolean = true
    val units: Set<String> = units.map(::normalizeUnitName).filter(String::isNotBlank).toSet()

    override fun onEnable(context: GameModeContext) {
        val banned = units
        if (banned.isEmpty()) return
        GlobalEventChannel.filter(CommandQueueEvent::class).subscribeAlways(context.eventOwner) { event ->
            if (!context.session.hasNativeAuthority() || banned.isEmpty()) return@subscribeAlways
            if (isBanned(event.command, banned)) event.cancel()
        }
        GlobalEventChannel.filter(SimulationBeforeTickEvent::class).subscribeAlways(context.eventOwner) { event ->
            val engine = event.engine
            if (engine.networkEngine.networked || engine.replayEngine.isPlayingReplay) return@subscribeAlways
            val pending = engine.commandController.localPendingCommands
            if (pending.isEmpty()) return@subscribeAlways
            if (!context.session.hasNativeAuthority(engine)) return@subscribeAlways
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val command = iterator.next() as? CommandControllerCommand ?: continue
                if (isBanned(command, banned)) iterator.remove()
            }
        }
    }

    private fun isBanned(command: CommandControllerCommand, banned: Set<String>): Boolean {
        val actionUnit = command.actionId?.getId()?.removePrefix("u_")?.let(::normalizeUnitName)
        val waypoint = command.waypoint
        val waypointUnit = if (waypoint?.type == WaypointType.build) {
            waypoint.unitType?.internalName?.let(::normalizeUnitName)
        } else null
        return actionUnit?.let(banned::contains) == true || waypointUnit?.let(banned::contains) == true
    }

    companion object {
        const val ID = "rwpp:ban-units"

        internal fun normalizeUnitName(value: String): String = value.trim().removePrefix("u_")
    }
}

/**
 * Applies a room speed multiplier to the native frame engine while active. Multiple differently
 * named speed modifiers compose multiplicatively; the session restores the pre-mode value.
 */
class GameSpeedMode(
    val multiplier: Float,
    override val id: String = ID,
    override val displayName: String = "Game speed ${multiplier}x",
    override val order: Int = 0,
    override val requires: Set<String> = emptySet(),
    override val conflictsWith: Set<String> = emptySet(),
) : GameMode {
    override val requiresHostAuthority: Boolean = true

    init {
        require(multiplier.isFinite() && multiplier >= 0f) { "Game speed multiplier must be finite and at least zero." }
    }

    companion object {
        const val ID = "rwpp:game-speed"
    }
}

/** Composable XML map modifier, applied on the host before the generated TMX is started. */
class MapTransformMode(
    override val id: String,
    override val displayName: String = id,
    override val order: Int = 0,
    override val requires: Set<String> = emptySet(),
    override val conflictsWith: Set<String> = emptySet(),
    val transformer: (XMLMap) -> Unit,
) : GameMode {
    override val requiresHostAuthority: Boolean = true
    override val transformsMap: Boolean = true

    override fun transformMap(map: XMLMap, context: GameModeContext) = transformer(map)

    companion object {
        const val LEGACY_ID = "rwpp:legacy-map-transform"
    }
}

