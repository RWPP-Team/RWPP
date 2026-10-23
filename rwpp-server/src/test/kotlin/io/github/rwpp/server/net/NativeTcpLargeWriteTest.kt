package io.github.rwpp.server.net

import io.netty.buffer.AbstractByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeTcpLargeWriteTest {
    @Test fun largeSnapshotPreservesNativeHeaderAndPayloadWithoutConsolidatingComponents() = fixture { test ->
        // More than sixteen components also catches accidental default-limit consolidation.
        val payload = ByteArray(17 * 1024 * 1024 + 37) { (it * 17 + 31).toByte() }
        val expected = payload.copyOf()
        assertTrue(test.socket.send(510, payload))
        payload.fill(0)
        test.pump()
        val frame = assertNotNull(test.channel.readOutbound<ByteBuf>())
        try {
            val composite = assertIs<CompositeByteBuf>(frame)
            val expectedComponents = (expected.size + 8 + COMPONENT_BYTES - 1) / COMPONENT_BYTES
            assertEquals(expectedComponents, composite.numComponents())
            assertEquals(expectedComponents, test.allocator.components.size)
            assertTrue(composite.isDirect)
            repeat(composite.numComponents()) { index ->
                assertTrue(composite.component(index).capacity() in 1..COMPONENT_BYTES)
            }
            assertEquals(expected.size + 8, frame.readableBytes())
            assertEquals(expected.size, frame.readInt())
            assertEquals(510, frame.readInt())
            val actual = ByteArray(expected.size); frame.readBytes(actual)
            assertContentEquals(expected, actual)
            assertFalse(frame.isReadable)
        } finally { frame.release() }
        test.assertAllReleased()
    }

    @Test fun componentAllocationFailureReleasesThePartialCompositeAndNotifiesOnce() = fixture(failAt = 3) { test ->
        val failure = CompletableFuture<String>()
        val notifications = AtomicInteger()
        test.socket.startReceiving({ 1024 }, { _, _ -> fail("No inbound frame was sent") }, { reason ->
            notifications.incrementAndGet(); failure.complete(reason)
        })
        val problem = assertFailsWith<IllegalStateException> {
            test.socket.send(510, ByteArray(2_500_123))
        }
        assertEquals("Injected component allocation failure", problem.message)
        assertTrue(failure.get(3, TimeUnit.SECONDS).contains("allocation"))
        assertTrue(test.socket.isClosed)
        assertFalse(test.channel.isActive)
        assertEquals(2, test.allocator.components.size)
        assertEquals(1, test.allocator.composites.size)
        test.assertAllReleased()
        assertEquals(1, notifications.get())
        assertFalse(test.socket.send(511, byteArrayOf(1)))
    }

    @Test fun immediateCloseReleasesAnOwnedDelayedLargeSnapshot() = fixture { test ->
        assertTrue(test.socket.send(1, byteArrayOf(1), 30_000))
        assertTrue(test.socket.send(510, ByteArray(1_500_123)))
        assertEquals(1, test.allocator.composites.size)
        assertTrue(test.allocator.components.any { it.refCnt() > 0 })
        test.socket.close()
        test.pump()
        assertFalse(test.channel.isActive)
        assertNull(test.channel.readOutbound<ByteBuf>())
        test.assertAllReleased()
    }

    @Test fun gracefulCloseWritesTheLargeSnapshotBeforeReleasingItsComponents() = fixture { test ->
        val payload = ByteArray(1_500_123) { (it % 127).toByte() }
        assertTrue(test.socket.send(510, payload))
        test.socket.closeAfterWrites()
        test.pump()
        assertFalse(test.channel.isActive)
        val frame = assertNotNull(test.channel.readOutbound<ByteBuf>())
        try {
            assertIs<CompositeByteBuf>(frame)
            assertEquals(payload.size, frame.readInt())
            assertEquals(510, frame.readInt())
            val actual = ByteArray(payload.size); frame.readBytes(actual)
            assertContentEquals(payload, actual)
            assertFalse(frame.isReadable)
            // The outbound queue owns the successful write until its consumer releases it.
            assertTrue(test.allocator.components.all { it.refCnt() > 0 })
        } finally { frame.release() }
        while (true) (test.channel.readOutbound<ByteBuf>() ?: break).release()
        test.assertAllReleased()
        assertFalse(test.socket.send(511, byteArrayOf(1)))
    }

    private fun fixture(failAt: Int = 0, block: (Fixture) -> Unit) {
        val previous = System.getProperty("rwpp.server.network.optimized")
        val runtime = try {
            System.setProperty("rwpp.server.network.optimized", "true")
            ServerTransportBackend()
        } finally {
            if (previous == null) System.clearProperty("rwpp.server.network.optimized")
            else System.setProperty("rwpp.server.network.optimized", previous)
        }
        val channel = EmbeddedChannel()
        val allocator = TrackingAllocator(failAt)
        channel.config().allocator = allocator
        val test = Fixture(channel, allocator, NativeTcpSocket(runtime, channel))
        try { block(test) }
        finally {
            test.socket.close()
            channel.finishAndReleaseAll()
            runtime.close()
            test.assertAllReleased()
        }
    }

    private class Fixture(val channel: EmbeddedChannel, val allocator: TrackingAllocator, val socket: NativeTcpSocket) {
        fun pump() { channel.runPendingTasks(); channel.runScheduledPendingTasks(); channel.runPendingTasks() }
        fun assertAllReleased() {
            assertTrue(allocator.components.all { it.refCnt() == 0 }, "A component retained its ByteBuf reference")
            assertTrue(allocator.composites.all { it.refCnt() == 0 }, "A composite retained its ByteBuf reference")
        }
    }

    private class TrackingAllocator(private val failAt: Int) : AbstractByteBufAllocator(true) {
        val components = ConcurrentLinkedQueue<ByteBuf>()
        val composites = ConcurrentLinkedQueue<CompositeByteBuf>()
        private val attempts = AtomicInteger()
        private fun allocate(initialCapacity: Int, maxCapacity: Int, direct: Boolean): ByteBuf {
            if (attempts.incrementAndGet() == failAt) throw IllegalStateException("Injected component allocation failure")
            return (if (direct) Unpooled.directBuffer(initialCapacity, maxCapacity) else Unpooled.buffer(initialCapacity, maxCapacity))
                .also { components.add(it) }
        }
        override fun newHeapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf = allocate(initialCapacity, maxCapacity, false)
        override fun newDirectBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf = allocate(initialCapacity, maxCapacity, true)
        override fun compositeDirectBuffer(maxNumComponents: Int): CompositeByteBuf =
            super.compositeDirectBuffer(maxNumComponents).also { composites.add(it) }
        // Direct production allocators use this hint when choosing ioBuffer's backing type.
        override fun isDirectBufferPooled() = true
    }

    private companion object { const val COMPONENT_BYTES = 1024 * 1024 }
}
