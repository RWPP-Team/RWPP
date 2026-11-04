package io.github.rwpp.api.net.impl

import io.github.rwpp.api.net.transport.TransportRuntime
import io.github.rwpp.server.net.NativeKcpServerSocket
import io.github.rwpp.server.net.NativeTcpServerSocket
import io.github.rwpp.server.net.NativeTcpSocket
import io.github.rwpp.server.net.ServerTransportBackend
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeKcpSocketTest {
    @BeforeTest
    fun installBackend() {
        TransportRuntime.install(ServerTransportBackend())
    }

    @AfterTest
    fun stopBackend() {
        TransportRuntime.close()
    }

    @Test
    fun welcomeSelectsKcpAndLargeNativeFramesRemainOrdered() {
        NativeKcpServerSocket(0).use { server ->
            NativeKcpSocket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 5000)
                assertEquals("KCP", client.transportName)
                (server.accept() as NativeKcpSocket).use { accepted ->
                    val received = LinkedBlockingQueue<Pair<Int, ByteArray>>()
                    val failures = LinkedBlockingQueue<String>()
                    accepted.startReceiving(
                        { NativeTcpSocket.MAX_PACKET_BYTES },
                        { type, bytes -> accepted.send(type, bytes) },
                        failures::offer
                    )
                    client.startReceiving(
                        { NativeTcpSocket.MAX_PACKET_BYTES },
                        { type, bytes -> received.offer(type to bytes) },
                        failures::offer
                    )
                    for (size in listOf(0, 17, 2_000_000, 64)) {
                        val bytes = ByteArray(size) { (it * 31).toByte() }
                        assertTrue(client.send(size, bytes))
                        val reply = received.poll(10, TimeUnit.SECONDS)
                        assertNotNull(reply, failures.toString())
                        assertEquals(size, reply.first)
                        assertContentEquals(bytes, reply.second)
                    }
                }
            }
        }
    }

    @Test
    fun silentUdpFallsBackToTcpWithinTheSameConnectBudget() {
        val old = System.getProperty("rwpp.network.probeMillis")
        System.setProperty("rwpp.network.probeMillis", "100")
        try {
            withTcpAndSilentUdp { server ->
                NativeKcpSocket().use { client ->
                    val started = System.nanoTime()
                    client.connect(InetSocketAddress("127.0.0.1", server.localPort), 3000)
                    assertEquals("TCP", client.transportName)
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
                    (server.accept() as NativeTcpSocket).use { accepted ->
                        val reply = LinkedBlockingQueue<ByteArray>()
                        accepted.startReceiving({ 10_000 }, { type, bytes -> accepted.send(type, bytes) }, {})
                        client.startReceiving({ 10_000 }, { _, bytes -> reply.offer(bytes) }, {})
                        assertTrue(client.send(1, byteArrayOf(1, 2, 3)))
                        assertContentEquals(byteArrayOf(1, 2, 3), reply.poll(3, TimeUnit.SECONDS))
                    }
                }
            }
        } finally {
            if (old == null) System.clearProperty("rwpp.network.probeMillis") else System.setProperty(
                "rwpp.network.probeMillis",
                old
            )
        }
    }

    private fun withTcpAndSilentUdp(block: (NativeTcpServerSocket) -> Unit) {
        // TCP and UDP have separate ephemeral-port allocators. Reserving one
        // protocol does not prove the same port is free for the other.
        repeat(20) {
            NativeTcpServerSocket(0).use { server ->
                val udp = try {
                    DatagramSocket(server.localPort)
                } catch (_: BindException) {
                    return@use
                }
                udp.use { block(server) }
                return
            }
        }
        error("Could not reserve a TCP/UDP test port")
    }

    @Test
    fun closeAfterWritesWaitsForSlowInboundDelivery() {
        NativeKcpServerSocket(0).use { server ->
            NativeKcpSocket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 5000)
                (server.accept() as NativeKcpSocket).use { accepted ->
                    val firstEntered = CountDownLatch(1)
                    val releaseReceiver = CountDownLatch(1)
                    val received = LinkedBlockingQueue<Int>()
                    val clientFailures = LinkedBlockingQueue<String>()
                    accepted.startReceiving({ 1024 }, { type, _ ->
                        if (type == 1) {
                            firstEntered.countDown()
                            check(releaseReceiver.await(4, TimeUnit.SECONDS)) { "Test receiver was not released" }
                        }
                        received.offer(type)
                    }, {})
                    client.startReceiving({ 1024 }, { _, _ -> }, clientFailures::offer)

                    assertTrue(client.send(1, byteArrayOf(1)))
                    assertTrue(client.send(2, byteArrayOf(2)))
                    assertTrue(firstEntered.await(3, TimeUnit.SECONDS), "First frame was not delivered")
                    client.closeAfterWrites()
                    try {
                        // Exceed the old fixed close delay while the first ordered callback is blocked.
                        Thread.sleep(650)
                        assertFalse(client.isClosed, "Sender closed before the receiver acknowledged delivery")
                    } finally {
                        releaseReceiver.countDown()
                    }

                    assertEquals(1, received.poll(3, TimeUnit.SECONDS))
                    assertEquals(2, received.poll(3, TimeUnit.SECONDS), "Trailing accepted frame was lost or reordered")
                    assertEquals(
                        "KCP connection closed",
                        clientFailures.poll(3, TimeUnit.SECONDS),
                        "Close confirmation did not finish"
                    )
                }
            }
        }
    }

    @Test
    fun failureBeforeReceiverInstallationIsDeliveredWhenInstalled() {
        NativeKcpSocket().use { socket ->
            socket.fail("early disconnect")
            val failures = LinkedBlockingQueue<String>()
            socket.startReceiving({ 1024 }, { _, _ -> }, failures::offer)
            assertEquals("early disconnect", failures.poll(3, TimeUnit.SECONDS))
        }
    }
}
