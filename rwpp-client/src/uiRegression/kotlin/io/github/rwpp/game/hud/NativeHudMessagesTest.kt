package io.github.rwpp.game.hud

import kotlin.test.*

class NativeHudMessagesTest {
    @Test
    fun chatSamplesTheNewestEightAndReversesRowsForDisplay() {
        assertEquals((0 until 12).toList(), nativeHudCandidateRange(events = true, entryCount = 12).toList())
        assertEquals((4 until 12).toList(), nativeHudCandidateRange(events = false, entryCount = 12).toList())
        assertEquals((0 until 4).toList(), nativeHudCandidateRange(events = false, entryCount = 4).toList())
        assertEquals(listOf(1, 2, 3), nativeHudDisplayOrder(events = true, rows = listOf(1, 2, 3)))
        assertEquals(listOf(3, 2, 1), nativeHudDisplayOrder(events = false, rows = listOf(1, 2, 3)))
    }

    @Test
    fun warLogExpirationOverridesDisplayPreferenceAndAlwaysShowOnlyOverridesHiding() {
        assertTrue(nativeWarLogEntryVisible(1_100L, 1_000L, 100L, true, false))
        assertFalse(nativeWarLogEntryVisible(1_101L, 1_000L, 100L, true, true))
        assertTrue(nativeWarLogEntryVisible(1_050L, 1_000L, 100L, false, true))
        assertFalse(nativeWarLogEntryVisible(1_050L, 1_000L, 100L, false, false))
    }

    @Test
    fun displayRowsAreReusedUntilSourceOrTypingProgressChanges() {
        val cache = NativeHudMessageDisplayCache<String>()
        var renderCount = 0
        fun rows(ageMillis: Long) = listOf(
            NativeHudMessageSnapshot(
                text = "hello",
                isChatMessage = true,
                sender = "p",
                ageMillis = ageMillis,
                revealMillisPerCharacter = 100,
            )
        )

        fun display(ageMillis: Long, presentationKey: Int = 0) = cache.update(
            rows = rows(ageMillis),
            nextPresentationKey = presentationKey,
            revealProgress = { nativeHudMessageRevealCharacterCount(it, showTeam = false, showTimestamp = false) },
        ) {
            renderCount++
            it.text
        }

        val initial = display(0L)
        assertSame(initial, display(99L), "Unchanged per-character progress must reuse the displayed list")
        assertEquals(1, renderCount)
        assertNotSame(initial, display(100L), "A newly revealed character invalidates the displayed list")
        assertEquals(2, renderCount)
        assertNotSame(display(100L), display(100L, presentationKey = 1), "Chat display settings invalidate rows")
        assertEquals(3, renderCount)
    }

    @Test
    fun emptyDisplayRowsAreSharedAndSourceExpirationInvalidatesThem() {
        val cache = NativeHudMessageDisplayCache<String>()
        val row = NativeHudMessageSnapshot(text = "event")
        val populated = cache.update(listOf(row)) { it.text }
        val empty = cache.update(emptyList()) { it.text }
        assertTrue(empty.isEmpty())
        assertSame(empty, cache.update(emptyList()) { it.text })
        val repopulated = cache.update(listOf(row)) { it.text }
        assertNotSame(populated, repopulated)
        assertEquals(listOf("event"), repopulated)
    }

    @Test
    fun typingProgressIncludesOnlyCurrentlyEnabledMetadata() {
        val row = NativeHudMessageSnapshot(
            text = "hi", isChatMessage = true, sender = "A", teamLetter = "B", timestamp = "1:02", ageMillis = 10_000,
            revealMillisPerCharacter = 100,
        )
        assertEquals(5, nativeHudMessageRevealCharacterCount(row, showTeam = false, showTimestamp = false))
        assertEquals(9, nativeHudMessageRevealCharacterCount(row, showTeam = true, showTimestamp = false))
        assertEquals(12, nativeHudMessageRevealCharacterCount(row, showTeam = false, showTimestamp = true))
        assertEquals(16, nativeHudMessageRevealCharacterCount(row, showTeam = true, showTimestamp = true))
    }
}
