package io.github.rwpp.api.game.impl

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientRoomJoinTicketTest {
    @Test
    fun humanInputDoesNotConsumeSocketOrHandshakeNetworkBudget() = runBlocking {
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket()
        val socket = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        val prompt = Any()
        ticket.promptOpened(prompt)
        val joining = async { ticket.awaitConnectionReady(socket, 30) }
        kotlinx.coroutines.delay(80)
        assertFalse(joining.isCompleted)
        socket.complete(Result.success(Unit))
        kotlinx.coroutines.delay(80)
        assertFalse(joining.isCompleted, "The human wait must survive socket-to-roster phase transition")
        ticket.promptClosed(prompt)
        ticket.roomReady.complete(Unit)
        assertTrue(joining.await())
    }

    @Test
    fun staleAnswerCannotResumeDeadlineOfANewerPrompt() = runBlocking {
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket()
        val first = Any();
        val second = Any()
        ticket.promptOpened(first)
        ticket.promptOpened(second)
        ticket.promptClosed(first)
        val joining = async {
            ticket.awaitConnectionReady(kotlinx.coroutines.CompletableDeferred(Result.success(Unit)), 30)
        }
        kotlinx.coroutines.delay(80)
        assertFalse(joining.isCompleted)
        ticket.promptClosed(second)
        ticket.roomReady.complete(Unit)
        assertTrue(joining.await())
    }

    @Test
    fun anUnresponsiveNetworkStillTimesOutWithoutHumanInput() = runBlocking {
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket()
        assertFalse(ticket.awaitConnectionReady(kotlinx.coroutines.CompletableDeferred(Result.success(Unit)), 20))
    }

    @Test
    fun roomReadyOnlyCompletesForTheSocketStartedByThatAttempt() = runBlocking {
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket()
        val expectedSocket = Socket()
        val staleSocket = Socket()
        ticket.expectedSocket = expectedSocket

        assertFalse(ticket.completeFrom(staleSocket))
        assertFalse(ticket.roomReady.isCompleted)

        assertTrue(ticket.completeFrom(expectedSocket))
        assertEquals(Unit, ticket.roomReady.await())
        assertFalse(ticket.completeFrom(expectedSocket))

        val cancelledTicket = NativeGameLifecycle.ClientRoomJoinTicket()
        cancelledTicket.expectedSocket = expectedSocket
        cancelledTicket.roomReady.cancel()
        assertFalse(cancelledTicket.completeFrom(expectedSocket))
    }

    @Test
    fun relayChildUsesItsTransportAncestorButRejectsUnrelatedSocketsAndCycles() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe

        fun connection(socket: Socket, parent: com.corrodinggames.rts.gameFramework.network.Connection? = null) =
            (unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.Connection::class.java)
                    as com.corrodinggames.rts.gameFramework.network.Connection).apply {
                this.socket = socket; this.parent = parent
            }

        val socket = Socket()
        val parent = connection(socket)
        val forwarded = connection(Socket(), parent)
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket().apply { expectedSocket = socket }
        assertTrue(ticket.owns(forwarded))
        assertFalse(ticket.owns(connection(Socket())))
        assertTrue(ticket.completeFromConnection(forwarded))
        val cycle = connection(Socket()).apply { this.parent = this }
        assertFalse(ticket.owns(cycle))
    }

    @Test
    fun nativeGameCanCommitBeforeTheSuspendedJoinContinuationResumes() {
        val field = GamePlatform::class.java.getDeclaredField("connectionAttempt").apply { isAccessible = true }
        val previous = field.get(GamePlatform)
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket()
        val attempt = GamePlatform.ClientJoinAttempt(ticket).apply { connectionStarted.set(true) }
        try {
            field.set(GamePlatform, attempt)
            assertFalse(GamePlatform.completeJoinServer(), "transport alone is not room/game readiness")
            ticket.roomReady.complete(Unit) // Native verification map/lobby, before readyForCommit is set.
            assertFalse(attempt.readyForCommit.get())
            assertTrue(GamePlatform.completeJoinServer())
            assertTrue(attempt.committed.get())
            GamePlatform.cancelJoinServer() // A disposing join page no longer owns this connection.
            assertFalse(attempt.cancelled.get())
            assertFalse(ticket.roomReady.isCancelled)
        } finally {
            field.set(GamePlatform, previous)
        }
    }

    @Test
    fun reconnectPacketCanTransferReadinessToTheNewTransportExactlyOnce() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe

        fun connection(socket: Socket) =
            (unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.Connection::class.java)
                    as com.corrodinggames.rts.gameFramework.network.Connection).apply { this.socket = socket }

        val original = Socket();
        val redirected = Socket();
        val unrelated = Socket()
        val ticket = NativeGameLifecycle.ClientRoomJoinTicket().apply { expectedSocket = original }
        assertFalse(ticket.acceptTransport(unrelated), "an arbitrary socket must not steal the join ticket")
        assertFalse(ticket.beginNativeRedirect(connection(unrelated)))
        assertTrue(ticket.beginNativeRedirect(connection(original)))
        assertTrue(ticket.acceptTransport(redirected))
        assertFalse(ticket.acceptTransport(unrelated), "redirect permission is consumed once")
        assertFalse(ticket.completeFrom(original), "the replaced transport must not complete the room")
        assertTrue(ticket.completeFrom(redirected))
    }

    @Test
    fun nativeReconnectWirePacketTransfersThePendingTicket() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val connection = unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.Connection::class.java)
                as com.corrodinggames.rts.gameFramework.network.Connection
        val original = Socket();
        val next = Socket()
        connection.socket = original
        val ticket = NativeGameLifecycle.beginClientRoomJoin().apply { expectedSocket = original }
        try {
            val packet = com.corrodinggames.rts.gameFramework.network.Packet(178).apply { this.connection = connection }
            val network =
                unsafe.allocateInstance(com.corrodinggames.rts.gameFramework.network.NetworkEngine::class.java)
                        as com.corrodinggames.rts.gameFramework.network.NetworkEngine
            network.networked = true
            io.github.rwpp.api.mixin.network.NativeNetworkLifecycleMixin.beginNetworkPacket(network, packet)
            assertTrue(ticket.acceptTransport(next), "PACKET_RECONNECT_TO (178) must authorize the replacement socket")
        } finally {
            NativeGameLifecycle.finishClientRoomJoin(ticket)
        }
    }
}
