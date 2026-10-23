package io.github.rwpp.server.validation

import io.github.rwpp.api.net.transport.ClientSink
import io.github.rwpp.api.net.transport.CountedServerTransport
import io.github.rwpp.api.net.transport.TransportConfig
import io.github.rwpp.api.net.transport.TransportListener
import io.github.rwpp.api.net.transport.TransportMessages
import io.github.rwpp.api.net.transport.TransportPeer
import io.github.rwpp.client.net.JdkTransportBackend
import io.github.rwpp.server.net.ServerTransportBackend
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkClientTransportTest {
    @Test
    fun jdkKcpClientExchangesMessagesAndReconnectsOnSameTransport() = withBackends { serverBackend, clientBackend ->
        val config = testConfig(conv = 0x4a444b31)
        val server = serverBackend.kcpServer(config) as CountedServerTransport
        try {
            val listener = EchoListener()
            server.start(listener)
            val address = assertNotNull(server.localAddress)
            val client = clientBackend.kcpClient(config.copy(port = address.port), "127.0.0.1", TransportMessages.DEFAULT)
            val sink = RecordingSink()
            try {
                val link = client.connect(sink)
                val smallMessages = (0 until 8).map { payload(10 + it, 96 + it) }
                smallMessages.forEach { assertTrue(link.send(it), "KCP rejected a small message") }
                smallMessages.forEach { expected ->
                    assertContentEquals(expected, awaitMessage(sink), "KCP echo order or contents changed")
                }

                val peer = assertNotNull(listener.connected.poll(5, TimeUnit.SECONDS), "KCP server did not accept the peer")
                val serverMessage = payload(40, 128)
                assertTrue(peer.trySend(serverMessage), "KCP server rejected a small message")
                assertContentEquals(serverMessage, awaitMessage(sink), "KCP server-to-client message changed")

                val fragmented = payload(99, 60 * 1024)
                assertTrue(link.send(fragmented), "KCP rejected a 60 KiB message")
                assertContentEquals(fragmented, awaitMessage(sink), "KCP fragmented echo changed")
                assertFalse(listener.sendRejected.get(), "KCP server echo queue rejected a message")

                link.close()
                peer.close()
                assertNotNull(listener.disconnected.poll(5, TimeUnit.SECONDS), "Server did not remove the first KCP peer")
                assertEquals(0, server.peerCount, "First closed KCP peer remained in the server session table")
                assertNull(sink.failures.poll(250, TimeUnit.MILLISECONDS), "A locally closed KCP client reported failure")

                val reconnectSink = RecordingSink()
                val reconnectLink = client.connect(reconnectSink)
                val reconnectMessage = payload(111, 512)
                assertTrue(reconnectLink.send(reconnectMessage), "KCP client rejected a frame after reconnect")
                assertContentEquals(reconnectMessage, awaitMessage(reconnectSink), "Reconnected KCP frame changed")
                val reconnectPeer = assertNotNull(
                    listener.connected.poll(5, TimeUnit.SECONDS), "Server did not accept the reconnected KCP peer",
                )
                reconnectLink.close()
                reconnectPeer.close()
                assertNotNull(listener.disconnected.poll(5, TimeUnit.SECONDS), "Server did not remove the reconnected KCP peer")
                assertEquals(0, server.peerCount, "Reconnected KCP peer remained in the server session table")
                assertNull(
                    reconnectSink.failures.poll(250, TimeUnit.MILLISECONDS),
                    "A locally closed reconnected KCP client reported failure",
                )
                client.close()
                client.close()
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun jdkTcpClientReceivesLargeServerFramesAndReconnectsOnSameTransport() = withBackends { serverBackend, clientBackend ->
        val config = testConfig(conv = 0x4a444b32, tcpLimit = 2048)
        val server = serverBackend.tcpServer(config, null) as CountedServerTransport
        try {
            val listener = EchoListener()
            server.start(listener)
            val address = assertNotNull(server.localAddress)
            val client = clientBackend.tcpClient(
                config.copy(port = address.port), "127.0.0.1", 1500, TransportMessages.DEFAULT,
            )
            val sink = RecordingSink()
            try {
                val link = client.connect(sink)
                val messages = (0 until 8).map { payload(60 + it, 1024) }
                messages.forEach { assertTrue(link.send(it), "TCP rejected a 1 KiB frame") }
                messages.forEach { expected ->
                    assertContentEquals(expected, awaitMessage(sink), "TCP frame order or contents changed")
                }

                val peer = assertNotNull(listener.connected.poll(5, TimeUnit.SECONDS), "TCP server did not accept the peer")
                val serverMessage = payload(80, 60 * 1024)
                assertTrue(peer.trySend(serverMessage), "TCP server rejected a 60 KiB frame")
                assertContentEquals(serverMessage, awaitMessage(sink), "TCP server-to-client frame changed")
                assertFalse(listener.sendRejected.get(), "TCP server echo queue rejected a frame")

                link.close()
                assertNotNull(listener.disconnected.poll(5, TimeUnit.SECONDS), "Server did not remove the first TCP peer")
                assertEquals(0, server.peerCount, "First closed TCP peer remained in the server session table")
                assertNull(sink.failures.poll(250, TimeUnit.MILLISECONDS), "A locally closed TCP client reported failure")

                val reconnectSink = RecordingSink()
                val reconnectLink = client.connect(reconnectSink)
                val reconnectMessage = payload(91, 1024)
                assertTrue(reconnectLink.send(reconnectMessage), "TCP client rejected a frame after reconnect")
                assertContentEquals(reconnectMessage, awaitMessage(reconnectSink), "Reconnected TCP frame changed")
                val reconnectPeer = assertNotNull(
                    listener.connected.poll(5, TimeUnit.SECONDS), "Server did not accept the reconnected TCP peer",
                )
                reconnectLink.close()
                assertNotNull(listener.disconnected.poll(5, TimeUnit.SECONDS), "Server did not remove the reconnected TCP peer")
                assertEquals(0, server.peerCount, "Reconnected TCP peer remained in the server session table")
                assertNull(
                    reconnectSink.failures.poll(250, TimeUnit.MILLISECONDS),
                    "A locally closed reconnected client reported failure",
                )
                reconnectPeer.close()
                peer.close()
                client.close()
                client.close()
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    private fun testConfig(conv: Int, tcpLimit: Int = 2048) = TransportConfig(
        bindHost = "127.0.0.1",
        port = 0,
        conv = conv,
        maxPayloadBytes = 60 * 1024,
        idleTimeoutMillis = 5_000,
        messageThreads = 1,
        maxTcpPayloadBytes = tcpLimit,
    )

    private fun payload(seed: Int, size: Int): ByteArray =
        ByteArray(size) { (seed * 37 + it * 19).toByte() }

    private fun awaitMessage(sink: RecordingSink): ByteArray =
        assertNotNull(sink.messages.poll(5, TimeUnit.SECONDS), "Client did not receive a message; failures=${sink.failures}")

    private fun withBackends(block: (ServerTransportBackend, JdkTransportBackend) -> Unit) {
        val serverBackend = ServerTransportBackend()
        val clientBackend = JdkTransportBackend()
        try {
            block(serverBackend, clientBackend)
        } finally {
            clientBackend.close()
            serverBackend.close()
        }
    }

    private class EchoListener : TransportListener {
        val connected = ArrayBlockingQueue<TransportPeer>(1)
        val disconnected = ArrayBlockingQueue<TransportPeer>(1)
        val sendRejected = AtomicBoolean()

        override fun connected(peer: TransportPeer) {
            connected.offer(peer)
        }

        override fun received(peer: TransportPeer, payload: ByteArray) {
            if (!peer.trySend(payload)) sendRejected.set(true)
        }

        override fun disconnected(peer: TransportPeer) {
            disconnected.offer(peer)
        }
    }

    private class RecordingSink : ClientSink {
        val messages = ArrayBlockingQueue<ByteArray>(32)
        val failures = ArrayBlockingQueue<String>(4)

        override fun message(payload: ByteArray) {
            messages.offer(payload)
        }

        override fun failure(reason: String) {
            failures.offer(reason)
        }
    }
}
