/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config

/** Replay metadata already read by the screen's single asynchronous catalog stream. */
internal data class MatchHistoryReplayCandidate(
    val fileName: String,
    val mapName: String,
    val timestampMillis: Long?,
)

private val replayAssociationNamePattern =
    Regex("""^(.*?)(?:\s*\[([^\[\]]+)])?\s*\(([^()]+)\)$""")

/** Extracts the map title from the native replay filename without resolving/loading a map. */
internal fun replayAssociationMapName(fileName: String): String {
    val cleanName = fileName.substringAfterLast('/').substringAfterLast('\\')
        .removeSuffixIgnoreCase(".replay")
        .trim()
    val mapName = replayAssociationNamePattern.find(cleanName)?.groupValues?.getOrNull(1)?.trim()
        ?: return ""
    return normalizeReplayAssociationMapName(mapName)
}

/** Revalidate the exact filename reference, including its parsed timestamp when available. */
internal fun matchHistoryReplayIdentityMatches(
    expectedFileName: String,
    currentFileName: String,
    expectedTimestampMillis: Long?,
    currentTimestampMillis: Long?,
): Boolean = isSafeReplayFileName(expectedFileName) && expectedFileName == currentFileName &&
        (expectedTimestampMillis == null || expectedTimestampMillis == currentTimestampMillis)

/**
 * Links legacy rows only when map name and recording time uniquely identify one replay and one
 * history row. Exact native filenames on newer rows are resolved separately and never fall back
 * to a guessed candidate when missing.
 */
internal fun resolveLegacyMatchReplayLinks(
    entries: List<MatchHistoryEntry>,
    candidates: List<MatchHistoryReplayCandidate>,
    timeToleranceMillis: Long = LEGACY_REPLAY_TIME_TOLERANCE_MILLIS,
): Map<String, String> {
    val tolerance = timeToleranceMillis.coerceAtLeast(0L)
    val reservedExactNames = entries.mapNotNull { it.replayFileName }.toSet()
    val uniqueCandidates = candidates
        .filter {
            isSafeReplayFileName(it.fileName) && it.timestampMillis != null &&
                    it.fileName !in reservedExactNames
        }
        .groupBy(MatchHistoryReplayCandidate::fileName)
        .filterValues { it.size == 1 }
        .values
        .map { it.single() }

    val legacyEntries = entries.filter {
        it.replayFileName == null && it.outcome != MatchOutcome.IN_PROGRESS
    }
    val candidatesByMapAndTime = uniqueCandidates
        .groupBy { normalizeReplayAssociationMapName(it.mapName) }
        .mapValues { (_, candidates) -> candidates.sortedBy { it.timestampMillis } }
    val choicesByEntry = legacyEntries.associate { entry ->
        val expectedMapName = normalizeReplayAssociationMapName(entry.mapPath)
        val timeIndex = candidatesByMapAndTime[expectedMapName].orEmpty()
        val choices = if (expectedMapName.isEmpty() || timeIndex.isEmpty()) emptyList() else {
            val start = entry.startedAtMillis
            val earliest = if (start < Long.MIN_VALUE + tolerance) Long.MIN_VALUE else start - tolerance
            val latest = if (start > Long.MAX_VALUE - tolerance) Long.MAX_VALUE else start + tolerance
            val firstIndex = lowerBoundReplayTime(timeIndex, earliest)
            val endIndex = upperBoundReplayTime(timeIndex, latest)
            timeIndex.subList(firstIndex, endIndex)
        }
        entry.id to choices
    }
    val candidateUseCount = choicesByEntry.values.flatten()
        .groupingBy(MatchHistoryReplayCandidate::fileName)
        .eachCount()

    return choicesByEntry.mapNotNull { (entryId, choices) ->
        val candidate = choices.singleOrNull() ?: return@mapNotNull null
        if (candidateUseCount[candidate.fileName] != 1) return@mapNotNull null
        entryId to candidate.fileName
    }.toMap()
}

/** Exact references are playable only when exactly one history row points to the filename. */
internal fun resolveExactMatchReplayLinks(
    entries: List<MatchHistoryEntry>,
    availableFileNames: Set<String>,
): Map<String, String> = entries
    .filter {
        it.outcome != MatchOutcome.IN_PROGRESS &&
                it.replayFileName != null && it.replayFileName in availableFileNames
    }
    .groupBy { it.replayFileName!! }
    .filterValues { it.size == 1 }
    .values
    .map { entry -> entry.single().id to entry.single().replayFileName!! }
    .toMap()

internal fun normalizeReplayAssociationMapName(value: String): String = value
    .trim()
    .replace('\\', '/')
    .substringAfterLast('/')
    .removeSuffixIgnoreCase(".tmx")
    .removeSuffixIgnoreCase(".rwsave")
    .replace(Regex("^\\[[^]]*]\\s*"), "")
    .replace('_', ' ')
    .trim()
    .lowercase()

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

private fun lowerBoundReplayTime(candidates: List<MatchHistoryReplayCandidate>, timestamp: Long): Int {
    var low = 0
    var high = candidates.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (candidates[middle].timestampMillis!! < timestamp) low = middle + 1 else high = middle
    }
    return low
}

private fun upperBoundReplayTime(candidates: List<MatchHistoryReplayCandidate>, timestamp: Long): Int {
    var low = 0
    var high = candidates.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (candidates[middle].timestampMillis!! <= timestamp) low = middle + 1 else high = middle
    }
    return low
}

internal const val LEGACY_REPLAY_TIME_TOLERANCE_MILLIS = 120_000L
