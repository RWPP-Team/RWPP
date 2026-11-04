/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.nativeaccess.NativeMessageAccess
import io.github.rwpp.game.nativeaccess.NativeMessageListAccess
import io.github.rwpp.game.nativeaccess.NativeWarLogAccess
import io.github.rwpp.game.nativeaccess.NativeWarLogListAccess

/** Plain snapshot of one native chat or war-log row; the UI owns Compose styling. */
internal data class NativeHudMessageSnapshot(
    val text: String,
    val isChatMessage: Boolean = false,
    val sender: String = "",
    val senderColor: Int = 0,
    val teamLetter: String? = null,
    val timestamp: String = "",
    val messageColor: Int = 0,
    val ageMillis: Long = 0L,
    val revealMillisPerCharacter: Int = 0,
)

/**
 * Samples native queues under their own lock and reuses published rows while their visible
 * contents, typing progress, and expiration state are unchanged.
 */
internal class NativeHudMessageSampler {
    private var owner: Any? = null
    private var events: Boolean? = null
    private var showTeam = false
    private var showTimestamp = false
    private var valid = false
    private var cachedRows: List<NativeHudMessageSnapshot> = emptyList()
    private var cachedRevealProgress = IntArray(0)

    @Synchronized
    fun sample(
        events: Boolean,
        now: Long,
        showTeam: Boolean = false,
        showTimestamp: Boolean = false,
    ): List<NativeHudMessageSnapshot> {
        val engine = GameEngine.getInstance()
        val queueOwner = if (events) engine.interfaceEngine.warLogInterface else engine.interfaceEngine.messageInterface
        return synchronized(queueOwner) {
            val entries = if (events) {
                (queueOwner as NativeWarLogListAccess).rwppItems()
            } else {
                (queueOwner as NativeMessageListAccess).rwppMessages()
            }
            val sameSession = valid && owner === queueOwner && this.events == events &&
                    this.showTeam == showTeam && this.showTimestamp == showTimestamp
            if (sameSession && sourceUnchanged(engine, entries, events, now, showTeam, showTimestamp)) {
                return@synchronized cachedRows
            }

            val captured = captureRows(engine, entries, events, now)
            cachedRows = captured
            if (cachedRevealProgress.size != captured.size) cachedRevealProgress = IntArray(captured.size)
            for (index in captured.indices) {
                cachedRevealProgress[index] = nativeHudMessageRevealCharacterCount(
                    captured[index], showTeam, showTimestamp,
                )
            }
            owner = queueOwner
            this.events = events
            this.showTeam = showTeam
            this.showTimestamp = showTimestamp
            valid = true
            cachedRows
        }
    }

    private fun sourceUnchanged(
        engine: GameEngine,
        entries: List<*>,
        events: Boolean,
        now: Long,
        showTeam: Boolean,
        showTimestamp: Boolean,
    ): Boolean {
        var rowIndex = 0
        val candidateIndices = nativeHudCandidateRange(events, entries.size).let { if (events) it else it.reversed() }
        for (entryIndex in candidateIndices) {
            val entry = entries[entryIndex] ?: continue
            if (events) {
                val item = entry as NativeWarLogAccess
                if (!nativeWarLogEntryVisible(
                        now, item.rwppLogTime(), item.rwppLogDuration(),
                        engine.settings.showWarLogOnScreen, item.rwppAlwaysShow(),
                    )
                ) continue
                val cached = cachedRows.getOrNull(rowIndex) ?: return false
                if (!sameNativeHudMessageSource(cached, item.rwppLogText())) return false
            } else {
                val item = entry as NativeMessageAccess
                val native = entry as com.corrodinggames.rts.gameFramework.gui.MessageInterfaceMessageItem
                if (!native.isVisible) continue
                val name = item.rwppSender().orEmpty()
                var player: Team? = null
                for (slot in 0 until Team.totalTeamSlots) {
                    val candidate = Team.getTeam(slot)
                    if (candidate?.name == name) {
                        player = candidate
                        break
                    }
                }
                val text = item.rwppMessage().orEmpty()
                val senderColor = player?.color ?: native.color
                val teamLetter = player?.allyTeam?.let { Team.getLetterForTeamId(it) }
                val timestamp = item.rwppTimeText().orEmpty()
                val ageMillis = now - item.rwppMessageTime()
                val cached = cachedRows.getOrNull(rowIndex) ?: return false
                if (!sameNativeHudMessageSource(
                        cached,
                        text,
                        isChatMessage = true,
                        sender = name,
                        senderColor = senderColor,
                        teamLetter = teamLetter,
                        timestamp = timestamp,
                        messageColor = native.color,
                        revealMillisPerCharacter = native.msPerChar,
                    )
                ) return false
                val progress = nativeHudMessageRevealCharacterCount(
                    text, true, name, teamLetter, timestamp, ageMillis, native.msPerChar,
                    showTeam, showTimestamp,
                )
                if (cachedRevealProgress.getOrNull(rowIndex) != progress) return false
            }
            rowIndex++
            if (events && rowIndex == 8) break
        }
        return rowIndex == cachedRows.size
    }

    private fun captureRows(
        engine: GameEngine,
        entries: List<*>,
        events: Boolean,
        now: Long,
    ): List<NativeHudMessageSnapshot> {
        var result: ArrayList<NativeHudMessageSnapshot>? = null
        val candidateIndices = nativeHudCandidateRange(events, entries.size).let { if (events) it else it.reversed() }
        for (entryIndex in candidateIndices) {
            val entry = entries[entryIndex] ?: continue
            if (events) {
                val item = entry as NativeWarLogAccess
                if (!nativeWarLogEntryVisible(
                        now, item.rwppLogTime(), item.rwppLogDuration(),
                        engine.settings.showWarLogOnScreen, item.rwppAlwaysShow(),
                    )
                ) continue
                val rows = result ?: ArrayList<NativeHudMessageSnapshot>(8).also { result = it }
                rows.add(NativeHudMessageSnapshot(text = item.rwppLogText()))
                if (rows.size == 8) break
            } else {
                val item = entry as NativeMessageAccess
                val native = entry as com.corrodinggames.rts.gameFramework.gui.MessageInterfaceMessageItem
                if (!native.isVisible) continue
                val name = item.rwppSender().orEmpty()
                var player: Team? = null
                for (slot in 0 until Team.totalTeamSlots) {
                    val candidate = Team.getTeam(slot)
                    if (candidate?.name == name) {
                        player = candidate
                        break
                    }
                }
                val rows = result ?: ArrayList<NativeHudMessageSnapshot>(8).also { result = it }
                rows.add(
                    NativeHudMessageSnapshot(
                        text = item.rwppMessage().orEmpty(),
                        isChatMessage = true,
                        sender = name,
                        senderColor = player?.color ?: native.color,
                        teamLetter = player?.allyTeam?.let { Team.getLetterForTeamId(it) },
                        timestamp = item.rwppTimeText().orEmpty(),
                        messageColor = native.color,
                        ageMillis = now - item.rwppMessageTime(),
                        revealMillisPerCharacter = native.msPerChar,
                    )
                )
            }
        }
        return result?.toList() ?: emptyList()
    }
}

internal fun nativeHudCandidateRange(events: Boolean, entryCount: Int): IntRange =
    (if (events) 0 else (entryCount - 8).coerceAtLeast(0)) until entryCount

internal fun <T> nativeHudDisplayOrder(events: Boolean, rows: List<T>): List<T> =
    if (events) rows else rows.asReversed()

/**
 * Reuses the Compose-facing rows while native source content and character reveal progress stay
 * unchanged. Expiration is reflected by the sampler removing rows, which invalidates the cache.
 */
internal class NativeHudMessageDisplayCache<T> {
    private var source: List<NativeHudMessageSnapshot> = emptyList()
    private var revealedCharacters = IntArray(0)
    private var presentationKey = 0
    private var hasPresentationKey = false
    private var display: List<T> = emptyList()

    fun update(
        rows: List<NativeHudMessageSnapshot>,
        nextPresentationKey: Int = 0,
        revealProgress: (NativeHudMessageSnapshot) -> Int = { 0 },
        render: (NativeHudMessageSnapshot) -> T,
    ): List<T> {
        if (rows.isEmpty()) {
            if (source.isEmpty() && hasPresentationKey && presentationKey == nextPresentationKey) return display
            source = emptyList()
            revealedCharacters = IntArray(0)
            presentationKey = nextPresentationKey
            hasPresentationKey = true
            display = emptyList()
            return display
        }

        var changed = !hasPresentationKey || presentationKey != nextPresentationKey || source.size != rows.size
        if (!changed) {
            for (index in rows.indices) {
                if (!sameNativeHudMessageSource(source[index], rows[index]) ||
                    revealedCharacters[index] != revealProgress(rows[index])
                ) {
                    changed = true
                    break
                }
            }
        }

        if (changed) {
            display = rows.map(render)
            if (revealedCharacters.size != rows.size) revealedCharacters = IntArray(rows.size)
            for (index in rows.indices) revealedCharacters[index] = revealProgress(rows[index])
            presentationKey = nextPresentationKey
            hasPresentationKey = true
        }
        source = rows
        return display
    }
}

private fun sameNativeHudMessageSource(
    cached: NativeHudMessageSnapshot,
    text: String,
    isChatMessage: Boolean = false,
    sender: String = "",
    senderColor: Int = 0,
    teamLetter: String? = null,
    timestamp: String = "",
    messageColor: Int = 0,
    revealMillisPerCharacter: Int = 0,
): Boolean = cached.text == text &&
        cached.isChatMessage == isChatMessage &&
        cached.sender == sender &&
        cached.senderColor == senderColor &&
        cached.teamLetter == teamLetter &&
        cached.timestamp == timestamp &&
        cached.messageColor == messageColor &&
        cached.revealMillisPerCharacter == revealMillisPerCharacter

private fun sameNativeHudMessageSource(first: NativeHudMessageSnapshot, second: NativeHudMessageSnapshot): Boolean =
    sameNativeHudMessageSource(
        first, second.text, second.isChatMessage, second.sender, second.senderColor, second.teamLetter,
        second.timestamp, second.messageColor, second.revealMillisPerCharacter,
    )

/** Reveal count matches the complete formatted chat line, including optional metadata. */
internal fun nativeHudMessageRevealCharacterCount(
    row: NativeHudMessageSnapshot,
    showTeam: Boolean,
    showTimestamp: Boolean,
): Int {
    return nativeHudMessageRevealCharacterCount(
        row.text, row.isChatMessage, row.sender, row.teamLetter, row.timestamp, row.ageMillis,
        row.revealMillisPerCharacter, showTeam, showTimestamp,
    )
}

private fun nativeHudMessageRevealCharacterCount(
    text: String,
    isChatMessage: Boolean,
    sender: String,
    teamLetter: String?,
    timestamp: String,
    ageMillis: Long,
    interval: Int,
    showTeam: Boolean,
    showTimestamp: Boolean,
): Int {
    if (!isChatMessage) return text.length
    val lineLength = text.length +
            (if (showTimestamp && timestamp.isNotBlank()) timestamp.length + 3 else 0) +
            (if (showTeam && teamLetter != null) teamLetter.length + 3 else 0) +
            (if (sender.isNotBlank()) sender.length + 2 else 0)
    return if (interval <= 0) lineLength else
        (ageMillis.coerceAtLeast(0) / interval).coerceAtMost(lineLength.toLong()).toInt()
}

/** Expiration takes precedence over showWarLogOnScreen/alwaysShow, matching the native HUD. */
internal fun nativeWarLogEntryVisible(
    now: Long,
    logTime: Long,
    duration: Long,
    showWarLogOnScreen: Boolean,
    alwaysShow: Boolean,
): Boolean = now <= logTime + duration && (showWarLogOnScreen || alwaysShow)
