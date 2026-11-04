package io.github.rwpp.server.net

import io.github.rwpp.api.net.transport.*
import io.netty.buffer.AbstractByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ServerNetworkBatchTest {
    @Test
    fun optimizedTcpPreservesBatchAndFinalCloseOrder() = tcpBatch(true)
    @Test
    fun unbatchedTcpPreservesBatchAndFinalCloseOrder() = tcpBatch(false)

    private fun tcpBatch(optimized: Boolean) {
        val previous = System.getProperty("rwpp.server.network.optimized")
        System.setProperty("rwpp.server.network.optimized", optimized.toString())
        val runtime = ServerTransportBackend()
        if (previous == null) System.clearProperty("rwpp.server.network.optimized") else System.setProperty(
            "rwpp.server.network.optimized",
            previous
        )
        val listener = NativeTcpServerSocket(runtime, 0)
        val client = NativeTcpSocket(runtime)
        var accepted: NativeTcpSocket? = null
        try {
            val server = CompletableFuture.supplyAsync { listener.accept() as NativeTcpSocket }
            client.connect(InetSocketAddress("127.0.0.1", listener.localPort), 3000)
            accepted = server.get(3, TimeUnit.SECONDS)
            val delivered = mutableListOf<Int>()
            val failure = CompletableFuture<String>()
            val latch = CountDownLatch(256)
            accepted.startReceiving({ 1024 }, { type, bytes ->
                assertEquals(123, type)
                synchronized(delivered) { delivered += ByteBuffer.wrap(bytes).int }
                latch.countDown()
            }, { failure.complete(it) })
            repeat(256) { sequence ->
                assertTrue(
                    client.send(
                        123,
                        ByteBuffer.allocate(8).putInt(sequence).putInt(sequence xor 0x1357).array()
                    )
                )
            }
            client.closeAfterWrites()
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Final accepted frames were lost during close")
            failure.get(10, TimeUnit.SECONDS)
            assertEquals((0..255).toList(), synchronized(delivered) { delivered.toList() })
        } finally {
            accepted?.close(); client.close(); listener.close(); runtime.close()
        }
    }

    @Test
    fun tcpDelayRemainsFifoAndCloseWaitsForDelayedFrame() {
        val runtime = ServerTransportBackend()
        val listener = NativeTcpServerSocket(runtime, 0)
        val client = NativeTcpSocket(runtime)
        var accepted: NativeTcpSocket? = null
        try {
            val server = CompletableFuture.supplyAsync { listener.accept() as NativeTcpSocket }
            client.connect(InetSocketAddress("127.0.0.1", listener.localPort), 3000)
            accepted = server.get(3, TimeUnit.SECONDS)
            val arrived = mutableListOf<Pair<Int, Long>>()
            val latch = CountDownLatch(2)
            val start = System.nanoTime()
            accepted.startReceiving(
                { 16 },
                { type, _ -> synchronized(arrived) { arrived += type to System.nanoTime() }; latch.countDown() },
                {})
            assertTrue(client.send(1, byteArrayOf(), 120))
            assertTrue(client.send(2, byteArrayOf()))
            client.closeAfterWrites()
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), arrived.map { it.first })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(arrived.last().second - start) >= 90)
        } finally {
            accepted?.close(); client.close(); listener.close(); runtime.close()
        }
    }

    @Test
    fun tcpSendSnapshotsPayloadBeforeCallerReusesItsArray() {
        val runtime = ServerTransportBackend()
        val listener = NativeTcpServerSocket(runtime, 0)
        var accepted: NativeTcpSocket? = null
        try {
            Socket("127.0.0.1", listener.localPort).use { client ->
                client.soTimeout = 5000
                accepted = listener.accept() as NativeTcpSocket
                val payload = ByteArray(4096) { (it % 127).toByte() }
                val expected = payload.copyOf()
                assertTrue(accepted.send(1, byteArrayOf(), 100))
                assertTrue(accepted.send(2, payload))
                payload.fill(99)
                accepted.closeAfterWrites()
                val input = DataInputStream(client.getInputStream())
                assertEquals(0, input.readInt()); assertEquals(1, input.readInt())
                assertEquals(expected.size, input.readInt()); assertEquals(2, input.readInt())
                val received = ByteArray(expected.size); input.readFully(received)
                assertContentEquals(expected, received)
                assertEquals(-1, input.read())
            }
        } finally {
            accepted?.close(); listener.close(); runtime.close()
        }
    }

    @Test
    fun tcpCloseReleasesPendingDelayedBuffersInBothModes() {
        for (optimized in listOf(true, false)) {
            val runtime = runtime(optimized)
            val channel = EmbeddedChannel()
            val allocator = TrackingAllocator()
            channel.config().allocator = allocator
            val socket = NativeTcpSocket(runtime, channel)
            try {
                assertTrue(socket.send(1, ByteArray(128), 30_000))
                assertTrue(socket.send(2, ByteArray(128)))
                assertEquals(2, allocator.buffers.size)
                socket.close()
                assertEquals(0, allocator.buffers[1].refCnt(), "Delayed frame leaked for optimized=$optimized")
                channel.finishAndReleaseAll()
                assertTrue(allocator.buffers.all { it.refCnt() == 0 })
                assertFalse(socket.send(3, byteArrayOf(1)))
            } finally {
                socket.close(); channel.finishAndReleaseAll(); runtime.close()
            }
        }
    }

    @Test
    fun coalescedFramesKeepSnapshotsAndDelayTheFollowingFrame() {
        val runtime = runtime(true)
        val channel = EmbeddedChannel()
        val socket = NativeTcpSocket(runtime, channel)
        try {
            val payload = byteArrayOf(10)
            assertTrue(socket.send(1, payload))
            payload[0] = 20
            assertTrue(socket.send(2, payload, 120))
            payload[0] = 30
            assertTrue(socket.send(3, payload))
            payload[0] = 40
            socket.closeAfterWrites()
            channel.runPendingTasks()
            channel.runScheduledPendingTasks()
            val combined = assertNotNull(channel.readOutbound<ByteBuf>())
            try {
                assertEquals(1, combined.readInt()); assertEquals(1, combined.readInt()); assertEquals(
                    10,
                    combined.readByte().toInt()
                )
                assertEquals(1, combined.readInt()); assertEquals(2, combined.readInt()); assertEquals(
                    20,
                    combined.readByte().toInt()
                )
                assertFalse(combined.isReadable)
            } finally {
                combined.release()
            }
            assertNull(channel.readOutbound<ByteBuf>())
            assertTrue(channel.isActive)
            Thread.sleep(150)
            channel.runScheduledPendingTasks()
            channel.runPendingTasks()
            val delayed = assertNotNull(channel.readOutbound<ByteBuf>())
            try {
                assertEquals(1, delayed.readInt()); assertEquals(3, delayed.readInt()); assertEquals(
                    30,
                    delayed.readByte().toInt()
                )
            } finally {
                delayed.release()
            }
            assertFalse(channel.isActive)
        } finally {
            socket.close(); channel.finishAndReleaseAll(); runtime.close()
        }
    }

    @Test
    fun tcpQueueOverflowReleasesAllOwnedSnapshotsInBothModes() {
        for (optimized in listOf(true, false)) {
            val runtime = runtime(optimized)
            val channel = EmbeddedChannel()
            val allocator = TrackingAllocator()
            channel.config().allocator = allocator
            val socket = NativeTcpSocket(runtime, channel)
            try {
                repeat(512) { assertTrue(socket.send(it, ByteArray(32))) }
                assertFalse(socket.send(513, ByteArray(32)))
                channel.finishAndReleaseAll()
                assertEquals(if (optimized) 1 else 512, allocator.buffers.size)
                assertTrue(
                    allocator.buffers.all { it.refCnt() == 0 },
                    "Rejected queue leaked a snapshot for optimized=$optimized"
                )
            } finally {
                socket.close(); channel.finishAndReleaseAll(); runtime.close()
            }
        }
    }

    @Test
    fun slowTcpCallbacksResumeBufferedFramesAfterReadPause() {
        val runtime = ServerTransportBackend()
        val listener = NativeTcpServerSocket(runtime, 0)
        var accepted: NativeTcpSocket? = null
        try {
            Socket("127.0.0.1", listener.localPort).use { client ->
                accepted = listener.accept() as NativeTcpSocket
                val frames = 2037
                val delivered = mutableListOf<Int>()
                val completed = CountDownLatch(frames)
                val disconnected = CompletableFuture<String>()
                accepted.startReceiving({ 32 }, { _, bytes ->
                    val sequence = ByteBuffer.wrap(bytes).int
                    if (sequence % 16 == 0) Thread.sleep(2)
                    synchronized(delivered) { delivered += sequence }
                    completed.countDown()
                }, { disconnected.complete(it) })
                val combined = ByteBuffer.allocate(frames * 12)
                repeat(frames) { sequence -> combined.putInt(4).putInt(100).putInt(sequence) }
                client.getOutputStream().write(combined.array())
                client.getOutputStream().flush()
                client.shutdownOutput()
                assertTrue(
                    completed.await(10, TimeUnit.SECONDS),
                    "Buffered TCP frames stopped at ${delivered.size}/$frames"
                )
                disconnected.get(5, TimeUnit.SECONDS)
                assertEquals((0 until frames).toList(), synchronized(delivered) { delivered.toList() })
            }
        } finally {
            accepted?.close(); listener.close(); runtime.close()
        }
    }

    @Test
    fun tcpQueueBoundsReservedCapacityIncludingUnusedBatchSpace() {
        val runtime = runtime(true)
        val channel = EmbeddedChannel()
        val allocator = TrackingAllocator()
        channel.config().allocator = allocator
        val socket = NativeTcpSocket(runtime, channel)
        try {
            // Two of these do not fit in one 256 KiB batch. The limit must charge
            // unused capacity as well, before reaching 512 frames or 64 MiB payload.
            val payload = ByteArray(129 * 1024)
            repeat(256) { assertTrue(socket.send(it, payload)) }
            assertFalse(socket.send(257, payload))
            channel.finishAndReleaseAll()
            assertEquals(256, allocator.buffers.size)
            assertTrue(allocator.buffers.all { it.refCnt() == 0 })
        } finally {
            socket.close(); channel.finishAndReleaseAll(); runtime.close()
        }
    }

    @Test
    fun kcpRejectsAckBeforeSessionAndTransfersFragmentedBatches() {
        val runtime = ServerTransportBackend()
        val config = TransportConfig(port = 0, conv = 0x12345678, maxPayloadBytes = 60 * 1024)
        val server = NettyKcpServerTransport(runtime, config)
        var client: ClientTransport? = null
        try {
            server.start(object : TransportListener {
                override fun connected(peer: TransportPeer) = Unit
                override fun received(peer: TransportPeer, payload: ByteArray) {
                    assertTrue(peer.trySend(payload))
                }

                override fun disconnected(peer: TransportPeer) = Unit
            })
            val port = server.localAddress!!.port
            DatagramSocket().use { source ->
                val ack = ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(config.conv).put(82).put(0).putShort(128).putInt(0).putInt(0).putInt(0).putInt(0).array()
                repeat(32) { source.send(DatagramPacket(ack, ack.size, InetSocketAddress("127.0.0.1", port))) }
            }
            Thread.sleep(50)
            assertEquals(0, server.peerCount)
            client = runtime.kcpClient(config.copy(port = port), "127.0.0.1", TransportMessages.DEFAULT)
            val received = mutableListOf<Int>()
            val complete = CountDownLatch(32)
            val failures = mutableListOf<String>()
            val connection = client.connect(object : ClientSink {
                override fun message(payload: ByteArray) {
                    assertEquals(16_384, payload.size)
                    synchronized(received) { received += ByteBuffer.wrap(payload).int }
                    complete.countDown()
                }

                override fun failure(reason: String) {
                    synchronized(failures) { failures += reason }
                }
            })
            repeat(32) { sequence -> assertTrue(connection.send(ByteBuffer.allocate(16_384).putInt(sequence).array())) }
            assertTrue(complete.await(10, TimeUnit.SECONDS), "KCP batches stalled: ${received.size}/32, $failures")
            assertEquals((0..31).toList(), synchronized(received) { received.toList() })
            assertEquals(1, server.peerCount)
            assertTrue(failures.isEmpty())
        } finally {
            client?.close(); server.close(); runtime.close()
        }
    }

    private fun runtime(optimized: Boolean): ServerTransportBackend {
        val previous = System.getProperty("rwpp.server.network.optimized")
        System.setProperty("rwpp.server.network.optimized", optimized.toString())
        return try {
            ServerTransportBackend()
        } finally {
            if (previous == null) System.clearProperty("rwpp.server.network.optimized")
            else System.setProperty("rwpp.server.network.optimized", previous)
        }
    }

    private class TrackingAllocator : AbstractByteBufAllocator(true) {
        val buffers = mutableListOf<ByteBuf>()
        override fun newHeapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.buffer(initialCapacity, maxCapacity).also { buffers.add(it) }

        override fun newDirectBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.directBuffer(initialCapacity, maxCapacity).also { buffers.add(it) }

        override fun isDirectBufferPooled() = false
    }
}
