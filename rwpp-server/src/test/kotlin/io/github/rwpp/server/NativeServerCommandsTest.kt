package io.github.rwpp.server

import io.github.rwpp.api.command.CommandSender
import io.github.rwpp.api.command.CommandVisibility
import io.github.rwpp.api.command.Commands
import io.github.rwpp.api.command.SERVER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeServerCommandsTest {
    @Test
    fun returnUsesNativeLobbyTransitionOnlyForAnActiveHostedMatch() {
        val commands = Commands()
        var state = NativeReturnState(isServer = false, gameStarted = true)
        val scheduled = mutableListOf<Float>()
        val replies = mutableListOf<String>()
        registerNativeReturnCommand(commands, { state }) { seconds -> scheduled += seconds }
        val sender = TestSender(replies)

        assertTrue(commands.execute("return", sender))
        assertEquals(emptyList(), scheduled)
        assertEquals("Only a hosted server can return to the lobby.", replies.removeAt(0))

        state = NativeReturnState(isServer = true, gameStarted = false)
        assertTrue(commands.execute("return", sender))
        assertEquals(emptyList(), scheduled)
        assertEquals("There is no active match to return from.", replies.removeAt(0))

        state = NativeReturnState(isServer = true, gameStarted = true)
        val ownerThread = Thread.currentThread()
        var scheduledThread: Thread? = null
        val threadBoundCommands = Commands()
        registerNativeReturnCommand(threadBoundCommands, { state }) { seconds ->
            scheduledThread = Thread.currentThread()
            scheduled += seconds
        }
        assertTrue(threadBoundCommands.execute("return", sender))
        assertEquals(listOf(0f), scheduled)
        assertEquals(ownerThread, scheduledThread, "The native schedule call stays on the command drain/game thread")
        assertEquals("Returning to the lobby.", replies.single())
    }

    @Test
    fun returnCommandIsVisibleOnlyToServerSenders() {
        val commands = Commands()
        registerNativeReturnCommand(
            commands,
            state = { NativeReturnState(isServer = true, gameStarted = true) },
            scheduleReturn = {},
        )
        val sender = TestSender(mutableListOf(), serverVisible = true)

        assertTrue("return" in commands.complete(listOf("re"), sender))
        assertTrue(sender.hasVisibility(CommandVisibility.SERVER))
        assertFalse(sender.hasVisibility("client"))
        val clientSender = TestSender(mutableListOf(), serverVisible = false)
        assertFalse("return" in commands.complete(listOf("re"), clientSender))
        assertFalse(commands.execute("return", clientSender))
    }

    @Test
    fun returnRejectsUnexpectedArgumentsWithoutSchedulingNativeTransition() {
        val commands = Commands()
        var scheduled = false
        registerNativeReturnCommand(
            commands,
            state = { NativeReturnState(isServer = true, gameStarted = true) },
            scheduleReturn = { scheduled = true },
        )
        val replies = mutableListOf<String>()

        assertTrue(commands.execute("return now", TestSender(replies)))
        assertFalse(scheduled)
        assertEquals(listOf("Usage: return"), replies)
    }

    private class TestSender(
        private val replies: MutableList<String>,
        private val serverVisible: Boolean = true,
    ) : CommandSender {
        override fun reply(message: String) {
            replies += message
        }

        override fun hasPermission(permission: String) = true
        override fun hasVisibility(visibility: String) =
            serverVisible && visibility == io.github.rwpp.api.command.CommandVisibility.SERVER
    }
}
