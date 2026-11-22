package io.github.rwpp.api.command

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CommandErrorsTest {
    @Test
    fun malformedInputAndHandlerValidationReplyToTheSender() {
        val replies = mutableListOf<String>()
        val sender = object : CommandSender {
            override fun reply(message: String) { replies += message }
            override fun hasPermission(permission: String) = false
            override fun hasVisibility(visibility: String) = true
        }
        val commands = Commands().apply {
            command("value", "Validate a value") { body { require(argument(0) == "ok") { "Invalid value" } } }
            command("private", "Permission check") { requirePermission("private") }
        }
        assertFalse(commands.execute("missing", sender))
        assertContains(replies.removeLast(), "missing")
        assertFalse(commands.execute("value \"", sender))
        assertEquals(CommandText.text("veil.commands.8151d66d"), replies.removeLast())
        assertFalse(commands.execute("private", sender))
        assertEquals(CommandText.text("veil.commands.59b77dbd"), replies.removeLast())
        assertFalse(commands.execute("value bad", sender))
        assertEquals("Invalid value", replies.removeLast())
    }
}
