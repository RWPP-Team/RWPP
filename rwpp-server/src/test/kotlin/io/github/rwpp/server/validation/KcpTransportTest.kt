package io.github.rwpp.api.net.transport

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KcpTransportTest {
    @kotlin.test.BeforeTest fun installBackend() { TransportRuntime.install(io.github.rwpp.server.net.ServerTransportBackend()) }
    @kotlin.test.AfterTest fun stopBackend() { TransportRuntime.close() }
    @Test
    fun realUdpConnectionRoundTripsHandshakeAndFragmentedPayload() {
        val config = TransportConfig(port = 0, conv = 0x5645494c, maxPayloadBytes = 8192, messageThreads = 1)
        val received = ArrayBlockingQueue<ByteArray>(4)
        val failures = ArrayBlockingQueue<String>(4)
        KcpServerTransport(config).use { server ->
            server.start(object : TransportListener {
                override fun connected(peer: TransportPeer) = Unit
                override fun received(peer: TransportPeer, payload: ByteArray) {
                    peer.trySend(payload)
                }
                override fun disconnected(peer: TransportPeer) = Unit
            })
            val address = assertNotNull(server.localAddress)
            KcpClientTransport(config.copy(port = address.port), "127.0.0.1").use { client ->
                val link = client.connect(object : ClientSink {
                    override fun message(payload: ByteArray) { received.offer(payload) }
                    override fun failure(reason: String) { failures.offer(reason) }
                })
                for (size in listOf(64, 5000)) {
                    val payload = ByteArray(size) { (it * 31).toByte() }
                    assertTrue(link.send(payload))
                    assertContentEquals(payload, received.poll(5, TimeUnit.SECONDS), failures.toString())
                }
                assertEquals(1, server.peerCount)
                link.close()
            }
        }
    }
}
