package io.github.rwpp.server.validation

import io.github.rwpp.api.net.kcp.Kcp
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kcp.Kcp as LegacyKcp

/** Uses the former third-party implementation as an independent wire peer. */
class KcpCompatibilityTest {
    @Test
    fun legacyJavaPeerAndPureCoreExchangeFragmentedMessagesUnderLoss() {
        PeerLink(lossPercent = 10, duplicatePercent = 15, jitter = 30, legacyMtu = 1400).use { link ->
            val fromPure = List(40) { payload(it, if (it % 5 == 0) 60 * 1024 else 40 + it * 37) }
            val fromLegacy = List(40) { payload(100 + it, if (it % 6 == 0) 60 * 1024 else 1 + it * 31) }
            fromPure.forEach { assertTrue(link.pure.send(it)) }
            fromLegacy.forEach(link::sendLegacy)
            link.run(30_000) {
                link.drain()
                link.receivedByLegacy.size == fromPure.size && link.receivedByPure.size == fromLegacy.size &&
                    link.pure.pendingSegments == 0 && link.legacy.waitSnd() == 0
            }
            assertMessages(fromPure, link.receivedByLegacy)
            assertMessages(fromLegacy, link.receivedByPure)
            assertEquals(0, link.pure.pendingSegments)
            assertEquals(0, link.legacy.waitSnd())
            assertFalse(link.pure.isDead)
        }
    }

    @Test
    fun originalPeerZeroWindowAndSessionChecksRemainCompatible() {
        PeerLink(lossPercent = 0, duplicatePercent = 0, jitter = 0, window = 4).use { link ->
            val messages = List(12) { payload(it, 20) }
            messages.forEach { assertTrue(link.pure.send(it)) }
            link.run(200) { false }
            assertEquals(8, link.pure.pendingSegments, "A full legacy application queue must stop the sender")
            link.run(1_000) {
                link.drain()
                link.receivedByLegacy.size == messages.size && link.pure.pendingSegments == 0
            }
            assertMessages(messages, link.receivedByLegacy)

            val wrongSessionDatagrams = mutableListOf<ByteArray>()
            val wrongSession = Kcp(CONVERSATION + 1, output = wrongSessionDatagrams::add)
            assertTrue(wrongSession.send(byteArrayOf(1, 2, 3)))
            wrongSession.flushPending(0)
            val bytes = wrongSessionDatagrams.single()
            assertFalse(link.pure.input(bytes, 1_100))
            val incoming = Unpooled.wrappedBuffer(bytes)
            try {
                assertEquals(-4, link.legacy.input(incoming, true, link.now))
            } finally {
                incoming.release()
            }
        }
    }

    private class PeerLink(
        private val lossPercent: Int,
        private val duplicatePercent: Int,
        private val jitter: Int,
        window: Int = 128,
        legacyMtu: Int = 1200,
    ) : AutoCloseable {
        private data class Delivery(val due: Int, val order: Long, val toPure: Boolean, val bytes: ByteArray)
        private val random = Random(60827)
        private var elapsed = 0
        private var serial = 0L
        private val epoch = System.currentTimeMillis()
        val now: Long get() = epoch + elapsed
        private val deliveries = PriorityQueue<Delivery>(compareBy<Delivery> { it.due }.thenBy { it.order })
        val pure = Kcp(CONVERSATION, sendWindow = window, receiveWindow = window,
            output = { enqueue(false, it) })
        val legacy = LegacyKcp(CONVERSATION) { buffer, _ ->
            try {
                enqueue(true, copy(buffer))
            } finally {
                // The legacy output callback owns the flush buffer.
                buffer.release()
            }
        }.apply {
            setMtu(legacyMtu)
            setSndWnd(window)
            setRcvWnd(window)
            nodelay(true, 10, 2, true)
            setAckNoDelay(true)
        }
        val receivedByPure = mutableListOf<ByteArray>()
        val receivedByLegacy = mutableListOf<ByteArray>()

        fun sendLegacy(message: ByteArray) {
            val buffer = Unpooled.wrappedBuffer(message)
            try {
                // send() retains slices; the caller still owns this reference.
                assertEquals(0, legacy.send(buffer))
            } finally {
                buffer.release()
            }
        }

        private fun enqueue(toPure: Boolean, bytes: ByteArray) {
            if (random.nextInt(100) < lossPercent) return
            deliveries.add(Delivery(elapsed + 2 + random.nextInt(jitter + 1), serial++, toPure, bytes))
            if (random.nextInt(100) < duplicatePercent) {
                deliveries.add(Delivery(elapsed + 2 + random.nextInt(jitter + 1), serial++, toPure, bytes.copyOf()))
            }
        }

        fun run(duration: Int, done: () -> Boolean) {
            val deadline = elapsed + duration
            while (elapsed <= deadline) {
                pure.update(now.toInt())
                legacy.update(now)
                while (deliveries.isNotEmpty() && deliveries.peek().due <= elapsed) {
                    val delivery = deliveries.remove()
                    if (delivery.toPure) {
                        assertTrue(pure.input(delivery.bytes, now.toInt()))
                    } else {
                        val buffer = Unpooled.wrappedBuffer(delivery.bytes)
                        try {
                            assertEquals(0, legacy.input(buffer, true, now))
                        } finally {
                            buffer.release()
                        }
                    }
                }
                if (done()) return
                elapsed++
            }
        }

        fun drain() {
            while (true) receivedByPure.add(pure.receive() ?: break)
            while (true) {
                val message = legacy.mergeRecv() ?: break
                try {
                    receivedByLegacy.add(copy(message))
                } finally {
                    message.release()
                }
            }
        }

        override fun close() {
            legacy.release()
            deliveries.clear()
        }

        private fun copy(buffer: ByteBuf): ByteArray =
            ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) }
    }

    private fun payload(sequence: Int, length: Int): ByteArray =
        ByteArray(length) { (sequence * 31 + it * 17).toByte() }

    private fun assertMessages(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertContentEquals(expected[it], actual[it], "Message $it") }
    }

    companion object {
        private const val CONVERSATION = 0x52575050
    }
}
