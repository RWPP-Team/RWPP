package io.github.rwpp.ui

import io.github.rwpp.game.chat.ChatTranscriptGeneration
import io.github.rwpp.game.chat.shouldClearChatAfterGameQuit
import io.github.rwpp.game.replay.ReplayBatchSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayAndChatLifecycleTest {
    @Test
    fun longPressStartsBatchSelectionAndAnotherLongPressCancelsIt() {
        var state = ReplayBatchSelection()

        state = state.longPress("first.replay")
        assertTrue(state.active)
        assertEquals(setOf("first.replay"), state.selectedNames)

        state = state.tap("second.replay")
        assertEquals(setOf("first.replay", "second.replay"), state.selectedNames)
        state = state.tap("first.replay")
        assertEquals(setOf("second.replay"), state.selectedNames)

        state = state.longPress("second.replay")
        assertFalse(state.active)
        assertTrue(state.selectedNames.isEmpty())
    }

    @Test
    fun deletingOneReplayKeepsBatchSelectionLimitedToExistingReplays() {
        val state = ReplayBatchSelection(active = true, selectedNames = setOf("first.replay", "second.replay"))

        val afterDelete = state.afterDeleting(
            deletedNames = setOf("first.replay"),
            remainingNames = setOf("second.replay", "unselected.replay"),
        )

        assertTrue(afterDelete.active, "Deleting an item should keep batch selection mode open")
        assertEquals(setOf("second.replay"), afterDelete.selectedNames)
    }

    @Test
    fun returningToConnectedBattleroomKeepsChatButLeavingGameClearsIt() {
        assertFalse(
            shouldClearChatAfterGameQuit(networked = true, singlePlayerServer = false, gameStarted = false),
            "Returning to the same multiplayer room must preserve its active chat transcript",
        )
        assertTrue(shouldClearChatAfterGameQuit(networked = false, singlePlayerServer = false, gameStarted = false))
        assertTrue(shouldClearChatAfterGameQuit(networked = true, singlePlayerServer = false, gameStarted = true))
        assertTrue(shouldClearChatAfterGameQuit(networked = true, singlePlayerServer = true, gameStarted = false))
    }

    @Test
    fun aQueuedChatLineFromBeforeClearIsRejected() {
        val generation = ChatTranscriptGeneration()
        val queuedMessageGeneration = generation.snapshot()

        generation.clear()

        assertFalse(generation.accepts(queuedMessageGeneration))
        assertTrue(generation.accepts(generation.snapshot()))
    }
}
