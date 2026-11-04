package io.github.rwpp.server.net

import io.netty.buffer.AbstractByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class NativeTcpAllocationTest {
    @Test
    fun readySecondAllocationCannotOvertakeTheUnreadyFirstFrame() = fixture { test ->
        val first = test.sendFirst()
        test.allocator.awaitFirst()
        val secondPayload = byteArrayOf(2)
        assertTrue(test.socket.send(2, secondPayload))
        secondPayload[0] = 99
        test.pump()
        assertNull(test.channel.readOutbound<ByteBuf>(), "A ready second frame passed the reserved first frame")

        test.allocator.releaseFirst.countDown()
        assertTrue(first.get(3, TimeUnit.SECONDS))
        test.pump()
        val frames = test.readFrames()
        assertEquals(listOf(1, 2), frames.map { it.first })
        assertEquals(listOf(1, 2), frames.map { it.second.single().toInt() })
    }

    @Test
    fun allocationCompletionDoesNotBypassTheFirstFramesNativeDelay() = fixture { test ->
        // A distant deadline lets the test verify non-delivery without sleeping or depending
        // on how quickly the allocation thread was scheduled on this machine.
        val first = test.sendFirst(delayAfterMillis = 30_000)
        test.allocator.awaitFirst()
        assertTrue(test.socket.send(2, byteArrayOf(2)))
        test.pump()
        assertNull(test.channel.readOutbound<ByteBuf>())

        test.allocator.releaseFirst.countDown()
        assertTrue(first.get(3, TimeUnit.SECONDS))
        test.pump()
        assertEquals(listOf(1), test.readFrames().map { it.first })
        assertTrue(test.channel.isActive)
        test.socket.close()
        assertTrue(test.allocator.buffers.all { it.refCnt() == 0 }, "The delayed second snapshot was not released")
    }

    @Test
    fun closeCancelsReservedAllocationAndReleasesItsLatePublishedBuffer() = fixture { test ->
        val first = test.sendFirst()
        test.allocator.awaitFirst()
        test.socket.close()
        assertFalse(test.channel.isActive)
        assertFalse(test.socket.send(2, byteArrayOf(2)))

        test.allocator.releaseFirst.countDown()
        assertFalse(
            first.get(3, TimeUnit.SECONDS),
            "An allocation reserved before immediate close was accepted afterward"
        )
        test.pump()
        assertNull(test.channel.readOutbound<ByteBuf>())
        assertTrue(test.allocator.buffers.all { it.refCnt() == 0 }, "The cancelled late allocation leaked its ByteBuf")
    }

    @Test
    fun closeAfterWritesWaitsForAnAlreadyReservedAllocation() = fixture { test ->
        val first = test.sendFirst()
        test.allocator.awaitFirst()
        test.socket.closeAfterWrites()
        assertTrue(test.channel.isActive, "Graceful close discarded a reserved frame")
        assertFalse(test.socket.send(2, byteArrayOf(2)), "Graceful close accepted a new frame")
        test.pump()
        assertNull(test.channel.readOutbound<ByteBuf>())
        assertTrue(test.channel.isActive)

        test.allocator.releaseFirst.countDown()
        assertTrue(first.get(3, TimeUnit.SECONDS), "Graceful close rejected its already reserved frame")
        test.pump()
        val frames = test.readFrames()
        assertEquals(listOf(1), frames.map { it.first })
        assertContentEquals(byteArrayOf(1), frames.single().second)
        assertFalse(test.channel.isActive)
        assertTrue(test.allocator.buffers.all { it.refCnt() == 0 })
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val previous = System.getProperty("rwpp.server.network.optimized")
        val runtime = try {
            System.setProperty("rwpp.server.network.optimized", "true")
            ServerTransportBackend()
        } finally {
            if (previous == null) System.clearProperty("rwpp.server.network.optimized")
            else System.setProperty("rwpp.server.network.optimized", previous)
        }
        val channel = EmbeddedChannel()
        val allocator = BlockingAllocator()
        channel.config().allocator = allocator
        val test = Fixture(runtime, channel, allocator)
        try {
            block(test)
        } finally {
            allocator.releaseFirst.countDown()
            test.socket.close()
            test.sender?.join(3000)
            channel.finishAndReleaseAll()
            runtime.close()
            assertFalse(test.sender?.isAlive == true, "Allocation sender did not terminate")
            assertTrue(allocator.buffers.all { it.refCnt() == 0 }, "A test allocation retained a ByteBuf")
        }
    }

    private class Fixture(
        runtime: ServerTransportBackend,
        val channel: EmbeddedChannel,
        val allocator: BlockingAllocator
    ) {
        val socket = NativeTcpSocket(runtime, channel)
        var sender: Thread? = null
        fun sendFirst(delayAfterMillis: Int = -1): CompletableFuture<Boolean> {
            val result = CompletableFuture<Boolean>()
            sender = Thread({
                try {
                    result.complete(socket.send(1, byteArrayOf(1), delayAfterMillis))
                } catch (problem: Throwable) {
                    result.completeExceptionally(problem)
                }
            }, "native-tcp-blocked-allocation-test").apply { isDaemon = true; start() }
            return result
        }

        fun pump() {
            channel.runPendingTasks(); channel.runScheduledPendingTasks(); channel.runPendingTasks()
        }

        fun readFrames(): List<Pair<Int, ByteArray>> {
            val frames = mutableListOf<Pair<Int, ByteArray>>()
            while (true) {
                val buffer = channel.readOutbound<ByteBuf>() ?: break
                try {
                    while (buffer.isReadable) {
                        val size = buffer.readInt()
                        val type = buffer.readInt()
                        val bytes = ByteArray(size); buffer.readBytes(bytes)
                        frames += type to bytes
                    }
                } finally {
                    buffer.release()
                }
            }
            return frames
        }
    }

    private class BlockingAllocator : AbstractByteBufAllocator(true) {
        val buffers = ConcurrentLinkedQueue<ByteBuf>()
        val releaseFirst = CountDownLatch(1)
        private val firstEntered = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        fun awaitFirst() {
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS), "The first allocation did not start")
        }

        private fun allocated(buffer: ByteBuf): ByteBuf {
            buffers.add(buffer)
            if (first.compareAndSet(true, false)) {
                firstEntered.countDown()
                try {
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "The blocked allocator was not released" }
                } catch (problem: Throwable) {
                    buffer.release(); throw problem
                }
            }
            return buffer
        }

        override fun newHeapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            allocated(Unpooled.buffer(initialCapacity, maxCapacity))

        override fun newDirectBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            allocated(Unpooled.directBuffer(initialCapacity, maxCapacity))

        override fun isDirectBufferPooled() = false
    }
}
