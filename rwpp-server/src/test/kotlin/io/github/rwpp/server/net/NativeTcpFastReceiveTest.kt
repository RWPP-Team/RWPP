package io.github.rwpp.server.net

import io.netty.buffer.AbstractByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.EventExecutor
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeTcpFastReceiveTest {
    @Test
    fun emptyReceiveQueueAllowsBoundedFastConsumptionOnIo() = fixture { test ->
        val fast = CompletableFuture<Pair<String, ByteArray>>()
        val ordinary = AtomicInteger()
        test.socket.startReceiving({ 1024 }, { _, _ -> ordinary.incrementAndGet() }, {}, { type, bytes ->
            if (type == 80) {
                fast.complete(Thread.currentThread().name to bytes)
                true
            } else false
        })
        test.write(frame(80, byteArrayOf(7, 8)))
        val (thread, bytes) = fast.get(3, TimeUnit.SECONDS)
        assertTrue(thread.startsWith("rwpp-server-io"), thread)
        assertContentEquals(byteArrayOf(7, 8), bytes)
        assertEquals(0, ordinary.get())
    }

    @Test
    fun payloadAbove64KiBBypassesFastAndReachesTheWorkerIntact() = fixture { test ->
        val payload = ByteArray(64 * 1024 + 1) { (it % 127).toByte() }
        val delivered = CompletableFuture<Pair<String, ByteArray>>()
        val fastCalls = AtomicInteger()
        test.socket.startReceiving({ 128 * 1024 }, { _, bytes ->
            delivered.complete(Thread.currentThread().name to bytes)
        }, {}, { _, _ -> fastCalls.incrementAndGet(); true })
        test.write(frame(80, payload))
        val (thread, received) = delivered.get(3, TimeUnit.SECONDS)
        assertTrue(thread.startsWith("rwpp-server-packets"), thread)
        assertContentEquals(payload, received)
        assertEquals(0, fastCalls.get(), "An oversized fast payload reached the I/O callback")
    }

    @Test
    fun payloadAt64KiBBoundaryRemainsEligibleForFastConsumption() = fixture { test ->
        val payload = ByteArray(64 * 1024) { (it * 17).toByte() }
        val delivered = CompletableFuture<Pair<String, ByteArray>>()
        val ordinaryCalls = AtomicInteger()
        test.socket.startReceiving({ 128 * 1024 }, { _, _ -> ordinaryCalls.incrementAndGet() }, {}, { _, bytes ->
            delivered.complete(Thread.currentThread().name to bytes)
            true
        })
        test.write(frame(80, payload))
        val (thread, received) = delivered.get(3, TimeUnit.SECONDS)
        assertTrue(thread.startsWith("rwpp-server-io"), thread)
        assertContentEquals(payload, received)
        assertEquals(0, ordinaryCalls.get())
    }

    @Test
    fun queuedSystemFrameFencesFastFramesUntilTheWorkerDrainsThem() = fixture { test ->
        val releaseWorker = CountDownLatch(1)
        val workerBlocked = CountDownLatch(1)
        val frames = ConcurrentLinkedQueue<Pair<Int, String>>()
        val fastFrames = ConcurrentLinkedQueue<Int>()
        val received = CountDownLatch(2)
        test.packetExecutor.execute { workerBlocked.countDown(); releaseWorker.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(workerBlocked.await(3, TimeUnit.SECONDS))
            test.socket.startReceiving({ 1024 }, { type, _ ->
                frames.add(type to Thread.currentThread().name); received.countDown()
            }, {}, { type, _ -> fastFrames.add(type); type == 80 })
            val first = frame(160, byteArrayOf(1))
            val second = frame(80, byteArrayOf(2))
            val decoded = test.afterInputBytes(first.size + second.size)
            test.write(first + second)
            decoded.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(160), fastFrames.toList(), "The fast frame passed an already queued system frame")
            assertTrue(frames.isEmpty())

            releaseWorker.countDown()
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(160, 80), frames.map { it.first })
            assertTrue(frames.all { it.second.startsWith("rwpp-server-packets") })
        } finally {
            releaseWorker.countDown()
        }
    }

    @Test
    fun inFlightSystemCallbackFencesFastFramesAndPreservesCompletionOrder() = fixture { test ->
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val complete = CountDownLatch(2)
        val events = ConcurrentLinkedQueue<String>()
        val fastFrames = ConcurrentLinkedQueue<Int>()
        val decoded = test.afterInputBytes(18)
        test.socket.startReceiving({ 1024 }, { type, _ ->
            assertTrue(Thread.currentThread().name.startsWith("rwpp-server-packets"))
            if (type == 160) {
                events.add("160-start")
                callbackEntered.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS)) { "Test callback was not released" }
                events.add("160-end")
            } else events.add(type.toString())
            complete.countDown()
        }, {}, { type, _ -> fastFrames.add(type); type == 80 })
        try {
            test.write(frame(160, byteArrayOf(1)))
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            test.write(frame(80, byteArrayOf(2)))
            decoded.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(160), fastFrames.toList(), "The fast frame passed an in-flight system callback")
            assertEquals(listOf("160-start"), events.toList())

            releaseCallback.countDown()
            assertTrue(complete.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("160-start", "160-end", "80"), events.toList())
        } finally {
            releaseCallback.countDown()
        }
    }

    @Test
    fun threeArgumentReceiverStillRunsOnThePacketWorker() = fixture { test ->
        val delivered = CompletableFuture<Pair<String, ByteArray>>()
        test.socket.startReceiving(
            { 1024 },
            { _, bytes -> delivered.complete(Thread.currentThread().name to bytes) },
            {})
        test.write(frame(80, byteArrayOf(4, 5)))
        val (thread, bytes) = delivered.get(3, TimeUnit.SECONDS)
        assertTrue(thread.startsWith("rwpp-server-packets"), thread)
        assertContentEquals(byteArrayOf(4, 5), bytes)
    }

    @Test
    fun declinedFastFrameRunsTheOrdinaryCallbackOnTheWorker() = fixture { test ->
        val events = ConcurrentLinkedQueue<String>()
        val delivered = CompletableFuture<ByteArray>()
        test.socket.startReceiving({ 1024 }, { _, bytes ->
            events.add(Thread.currentThread().name)
            delivered.complete(bytes)
        }, {}, { _, _ -> events.add(Thread.currentThread().name); false })
        test.write(frame(80, byteArrayOf(9)))
        assertContentEquals(byteArrayOf(9), delivered.get(3, TimeUnit.SECONDS))
        assertEquals(2, events.size)
        assertTrue(events.first().startsWith("rwpp-server-io"))
        assertTrue(events.last().startsWith("rwpp-server-packets"))
    }

    @Test
    fun fastCallbackExceptionClosesOnceAndReleasesInboundAndQueuedOutput() {
        val runtime = optimizedRuntime()
        val channel = EmbeddedChannel()
        val allocator = TrackingAllocator()
        channel.config().allocator = allocator
        val socket = NativeTcpSocket(runtime, channel)
        val input = Unpooled.wrappedBuffer(frame(80, byteArrayOf(1)))
        val ordinary = AtomicInteger()
        val failures = AtomicInteger()
        val stopped = CompletableFuture<String>()
        try {
            assertTrue(socket.send(1, byteArrayOf(1), 30_000))
            assertTrue(socket.send(2, byteArrayOf(2)))
            socket.startReceiving({ 1024 }, { _, _ -> ordinary.incrementAndGet() }, { reason ->
                failures.incrementAndGet(); stopped.complete(reason)
            }, { _, _ -> throw IllegalStateException("fast callback failed") })
            channel.writeInbound(input)
            channel.runPendingTasks()
            assertTrue(stopped.get(3, TimeUnit.SECONDS).contains("fast callback failed"))
            assertTrue(socket.isClosed)
            assertFalse(channel.isActive)
            channel.finishAndReleaseAll()
            assertEquals(0, input.refCnt())
            assertTrue(allocator.buffers.all { it.refCnt() == 0 })
            assertEquals(0, ordinary.get())
            assertEquals(1, failures.get())
            assertFalse(socket.send(3, byteArrayOf(3)))
        } finally {
            socket.close(); channel.finishAndReleaseAll(); runtime.close()
            if (input.refCnt() > 0) input.release()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val runtime = optimizedRuntime()
        val server = NativeTcpServerSocket(runtime, 0)
        var raw: Socket? = null
        var accepted: NativeTcpSocket? = null
        try {
            raw = Socket("127.0.0.1", server.localPort).apply { tcpNoDelay = true }
            accepted = server.accept() as NativeTcpSocket
            block(Fixture(accepted, raw))
        } finally {
            accepted?.close(); raw?.close(); server.close(); runtime.close()
        }
    }

    private class Fixture(val socket: NativeTcpSocket, private val raw: Socket) {
        val packetExecutor = NativeTcpSocket::class.java.getDeclaredField("executor").let {
            it.isAccessible = true; it.get(socket) as EventExecutor
        }
        private val channel = NativeTcpSocket::class.java.getDeclaredField("channel").let {
            it.isAccessible = true; it.get(socket) as Channel
        }

        fun write(bytes: ByteArray) {
            raw.getOutputStream().write(bytes); raw.getOutputStream().flush()
        }

        /** Resolve only after the decoder processed all bytes, even if TCP split the write. */
        fun afterInputBytes(expected: Int): CompletableFuture<Unit> {
            val result = CompletableFuture<Unit>()
            val observer = object : ChannelInboundHandlerAdapter() {
                private var bytes = 0
                override fun channelRead(context: ChannelHandlerContext, message: Any) {
                    if (message is ByteBuf) bytes += message.readableBytes()
                    context.fireChannelRead(message)
                    if (bytes >= expected) result.complete(Unit)
                }
            }
            // Pipeline changes from the test thread are asynchronous. Wait for
            // installation before the peer writes, otherwise readable I/O may
            // be selected before the queued addFirst task and escape observation.
            channel.eventLoop().submit { channel.pipeline().addFirst(observer) }.syncUninterruptibly()
            return result
        }
    }

    private fun frame(type: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(bytes.size + 8).putInt(bytes.size).putInt(type).put(bytes).array()

    private fun optimizedRuntime(): ServerTransportBackend {
        val previous = System.getProperty("rwpp.server.network.optimized")
        return try {
            System.setProperty("rwpp.server.network.optimized", "true")
            ServerTransportBackend()
        } finally {
            if (previous == null) System.clearProperty("rwpp.server.network.optimized")
            else System.setProperty("rwpp.server.network.optimized", previous)
        }
    }

    private class TrackingAllocator : AbstractByteBufAllocator(true) {
        val buffers = ConcurrentLinkedQueue<ByteBuf>()
        override fun newHeapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.buffer(initialCapacity, maxCapacity).also { buffers.add(it) }

        override fun newDirectBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            Unpooled.directBuffer(initialCapacity, maxCapacity).also { buffers.add(it) }

        override fun isDirectBufferPooled() = false
    }
}
