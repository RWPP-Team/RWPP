package io.github.rwpp.api.net.impl

import io.github.rwpp.api.net.transport.TransportRuntime
import io.github.rwpp.server.net.NativeTcpServerSocket
import io.github.rwpp.server.net.NativeTcpSocket
import io.github.rwpp.server.net.ServerTransportBackend
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativeTcpSocketTest {
    @BeforeTest
    fun installBackend() {
        TransportRuntime.install(ServerTransportBackend())
    }

    @AfterTest
    fun stopBackend() {
        TransportRuntime.close()
    }

    @Test
    fun fragmentedAndCoalescedNativeFramesPreserveOrderAndWireFormat() {
        NativeTcpServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 5000
                (server.accept() as NativeTcpSocket).use { accepted ->
                    val frames = LinkedBlockingQueue<Pair<Int, ByteArray>>()
                    accepted.startReceiving({ 1_000_000 }, { type, bytes ->
                        assertTrue(Thread.currentThread().name.startsWith("rwpp-server-packets"))
                        frames.add(type to bytes)
                        accepted.send(type + 1, bytes)
                    }, {})
                    val output = DataOutputStream(client.getOutputStream())
                    output.writeInt(3); output.flush()
                    output.writeInt(500); output.write(byteArrayOf(1, 2, 3))
                    output.writeInt(0); output.writeInt(502); output.flush()
                    val input = DataInputStream(client.getInputStream())
                    assertEquals(3, input.readInt()); assertEquals(501, input.readInt())
                    assertContentEquals(byteArrayOf(1, 2, 3), ByteArray(3).also(input::readFully))
                    assertEquals(0, input.readInt()); assertEquals(503, input.readInt())
                    assertEquals(500, frames.poll(5, TimeUnit.SECONDS)?.first)
                    assertEquals(502, frames.poll(5, TimeUnit.SECONDS)?.first)
                }
            }
        }
    }

    @Test
    fun clientAndServerNettySocketsExchangeLargeModFrames() {
        NativeTcpServerSocket(0).use { server ->
            NativeTcpSocket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 5000)
                (server.accept() as NativeTcpSocket).use { accepted ->
                    val received = CompletableFuture<ByteArray>()
                    accepted.startReceiving({ 10_000 }, { type, _ ->
                        assertEquals(500, type)
                        assertTrue(accepted.send(510, ByteArray(2_000_000) { (it % 127).toByte() }))
                    }, {})
                    client.startReceiving({ NativeTcpSocket.MAX_PACKET_BYTES }, { type, bytes ->
                        assertEquals(510, type); received.complete(bytes)
                    }, { received.completeExceptionally(IllegalStateException(it)) })
                    assertTrue(client.send(500, byteArrayOf(1)))
                    assertContentEquals(ByteArray(2_000_000) { (it % 127).toByte() }, received.get(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun oversizedHeaderClosesBeforeTheDeclaredBodyArrives() {
        NativeTcpServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 5000
                (server.accept() as NativeTcpSocket).use { accepted ->
                    val stopped = CompletableFuture<String>()
                    accepted.startReceiving(
                        { 10_000 },
                        { _, _ -> fail("Oversized packet was delivered") },
                        stopped::complete
                    )
                    DataOutputStream(client.getOutputStream()).apply { writeInt(50_000_000); writeInt(510); flush() }
                    stopped.get(5, TimeUnit.SECONDS)
                    assertEquals(-1, client.getInputStream().read())
                }
            }
        }
    }
}
