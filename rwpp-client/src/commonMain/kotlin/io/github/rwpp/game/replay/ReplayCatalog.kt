/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.api.game.map.Replay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

data class ParsedReplayItem(
    val rawReplay: Replay,
    val id: Int,
    val fileName: String,
    val mapName: String,
    val map: GameMap,
    val playerCountStr: String?,
    val versionStr: String,
    val dateTimeStr: String,
    val date: LocalDate? = null,
)

internal data class ReplayBatchSelection(
    val active: Boolean = false,
    val selectedNames: Set<String> = emptySet(),
) {
    fun tap(name: String): ReplayBatchSelection = if (!active) this else copy(
        selectedNames = if (name in selectedNames) selectedNames - name else selectedNames + name,
    )

    /** A first long press starts selection and includes that replay; another cancels the mode. */
    fun longPress(name: String): ReplayBatchSelection = if (active) {
        ReplayBatchSelection()
    } else {
        ReplayBatchSelection(active = true, selectedNames = setOf(name))
    }

    fun afterDeleting(deletedNames: Set<String>, remainingNames: Set<String>): ReplayBatchSelection = copy(
        selectedNames = (selectedNames - deletedNames).intersect(remainingNames),
    )
}

@Suppress("ConstantLocale")
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy HH.mm.ss", Locale.getDefault())

private val replayNameRegex = """^(.*?)(?:\s*\[([^\[\]]+)])?\s*\(([^()]+)\)$""".toRegex()

/** Precomputed name lookup so parsing a large replay catalog does not rescan every map per file. */
internal class ReplayMapLookup(availableMaps: List<GameMap>) {
    private val byName = LinkedHashMap<String, GameMap>()

    init {
        availableMaps.forEach { map ->
            byName.putIfCatalogKeyMissing(map.displayName().replayLookupKey(), map)
            byName.putIfCatalogKeyMissing(map.mapName.replayLookupKey(), map)
        }
    }

    fun find(name: String): GameMap? = byName[name.replayLookupKey()]
}

private fun MutableMap<String, GameMap>.putIfCatalogKeyMissing(key: String, value: GameMap) {
    if (key.isNotEmpty() && key !in this) this[key] = value
}

private fun String.replayLookupKey(): String = trim().lowercase(Locale.ROOT)

/** Parse the native replay filename and match its map without depending on the Compose selection screen. */
internal fun parseReplay(replay: Replay, availableMaps: List<GameMap>): ParsedReplayItem =
    parseReplay(replay, ReplayMapLookup(availableMaps))

internal fun parseReplay(replay: Replay, mapLookup: ReplayMapLookup): ParsedReplayItem {
    val rawName = replay.displayName()
    val id = replay.id
    val cleanName = rawName.removeSuffix(".replay").trim()

    val match = replayNameRegex.find(cleanName)
    val (extractedMapName, versionStr, dateTimeStr, playerCountStr) = if (match != null) {
        val mapAndPlayer = match.groupValues[1].trim()
        val version = match.groupValues[2].ifBlank { "UNKNOWN" }
        val dateTime = match.groupValues[3].trim()
        val playerCapRegex = """\((.*?p)\)""".toRegex(RegexOption.IGNORE_CASE)
        val playerCount = playerCapRegex.find(mapAndPlayer)?.groupValues?.get(1)
        Quadruple(mapAndPlayer, version, dateTime, playerCount)
    } else {
        Quadruple(cleanName, "UNKNOWN", "N/A", null)
    }

    val matchedMap = mapLookup.find(extractedMapName) ?: NetworkMap(extractedMapName)

    return ParsedReplayItem(
        rawReplay = replay,
        id = id,
        fileName = rawName,
        mapName = matchedMap.displayName(),
        map = matchedMap,
        playerCountStr = playerCountStr,
        versionStr = versionStr,
        dateTimeStr = dateTimeStr,
        date = parseReplayDate(dateTimeStr),
    )
}

private fun parseReplayDate(value: String): LocalDate? = try {
    LocalDateTime.parse(value.trim(), DATE_TIME_FORMATTER).toLocalDate()
} catch (_: Exception) {
    null
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
