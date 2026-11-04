package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionLifecycleTest {
    @Test
    fun submittingThenClosingNeverSendsCancellationAsASecondReply() {
        val replies = mutableListOf<String?>()
        val question = UI.Question("Search units", "query", { replies.add(it) }, allowEmptyReply = true)
        assertTrue(question.replyOnce(""))
        assertFalse(question.replyOnce(null))
        assertEquals(listOf<String?>(""), replies)
        val cancelled = UI.Question("Search units", "query", { replies.add(it) })
        assertTrue(cancelled.replyOnce(null))
        assertFalse(cancelled.replyOnce(null))
        assertEquals(listOf("", null), replies)
    }
}
