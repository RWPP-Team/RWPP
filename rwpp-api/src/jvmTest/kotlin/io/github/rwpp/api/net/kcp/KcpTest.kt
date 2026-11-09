package io.github.rwpp.api.net.kcp

import java.util.PriorityQueue
import kotlin.collections.ArrayDeque
import kotlin.random.Random
import kotlin.test.*

class KcpTest {
    @Test
    fun standardWireHeaderAndAtomicMalformedInput() {
        val datagrams = mutableListOf<ByteArray>()
        val sender = Kcp(0x52575050, output = datagrams::add)
        assertTrue(sender.send(byteArrayOf(7, 8, 9)))
        sender.update(0x01020304)
        assertContentEquals(
            byteArrayOf(
                0x50, 0x50, 0x57, 0x52, 81, 0, 0x80.toByte(), 0,
                4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 7, 8, 9,
            ), datagrams.single()
        )
        val acks = mutableListOf<ByteArray>()
        val receiver = Kcp(0x52575050, output = acks::add)
        val valid = datagrams.single()
        // The first segment is valid; a truncated second segment rejects both.
        assertFalse(receiver.input(valid + byteArrayOf(1, 2, 3), 10))
        assertTrue(acks.isEmpty())
        assertNull(receiver.receive())
        assertTrue(receiver.input(valid, 10))
        assertContentEquals(byteArrayOf(7, 8, 9), receiver.receive())
        assertEquals(82, acks.single()[4].toInt())
        assertTrue(sender.input(acks.single(), 20))
        assertEquals(0, sender.pendingSegments)
        assertTrue(receiver.input(valid, 30))
        assertNull(receiver.receive())
        assertEquals(2, acks.size, "A retransmitted delivered fragment must still be ACKed")

        for (offset in listOf(0, 4, 5, 20)) {
            val malformed = valid.copyOf()
            malformed[offset] = 0xff.toByte()
            assertFalse(receiver.input(malformed, 40), "Invalid field at $offset")
        }
    }

    @Test
    fun lossDuplicationAndReorderingDeliverBidirectionallyExactlyOnce() {
        val link = Link(seed = 8219, lossPercent = 12, duplicatePercent = 20, jitter = 45)
        val expectedA = List(80) { index -> payload(index, if (index % 9 == 0) 60 * 1024 else 31 + index * 11) }
        val expectedB = List(80) { index -> payload(1000 + index, if (index % 11 == 0) 32 * 1024 else index) }
        expectedA.forEach { assertTrue(link.a.send(it)) }
        expectedB.forEach { assertTrue(link.b.send(it)) }
        link.run(30_000) {
            link.drain()
            link.receivedByB.size == expectedA.size && link.receivedByA.size == expectedB.size &&
                    link.a.pendingSegments == 0 && link.b.pendingSegments == 0
        }
        assertMessages(expectedA, link.receivedByB)
        assertMessages(expectedB, link.receivedByA)
        assertFalse(link.a.isDead)
        assertFalse(link.b.isDead)
    }

    @Test
    fun timestampsAndSequenceNumbersWrap() {
        val link = Link(
            seed = 9, lossPercent = 5, duplicatePercent = 10, jitter = 10,
            start = Int.MAX_VALUE - 70
        )
        // Starting close to wrap avoids billions of packets while exercising
        // the same wire arithmetic used by a long-lived connection.
        val initial = -4
        setInt(link.a, "sendNext", initial)
        setInt(link.a, "sendUnacknowledged", initial)
        setInt(link.b, "receiveNext", initial)
        val messages = List(20) { payload(it, 1800) }
        messages.forEach { assertTrue(link.a.send(it)) }
        link.run(10_000) {
            link.drain()
            link.receivedByB.size == messages.size && link.a.pendingSegments == 0
        }
        assertMessages(messages, link.receivedByB)
        assertFalse(link.a.isDead)
    }

    @Test
    fun fastRetransmitRepairsAHoleBeforeTheInitialRto() {
        var now = 0
        var dropped = false
        var repairedAt: Int? = null
        val toA = ArrayDeque<ByteArray>()
        val toB = ArrayDeque<ByteArray>()
        val a = Kcp(7, mtu = 128, output = { datagram ->
            val sequence = read32(datagram, 12)
            if (sequence == 0 && !dropped) dropped = true
            else {
                if (sequence == 0) repairedAt = now
                toB.addLast(datagram)
            }
        })
        val b = Kcp(7, mtu = 128, output = toA::addLast)
        repeat(4) { assertTrue(a.send(payload(it, 100))) }
        while (now < 190 && repairedAt == null) {
            a.update(now)
            b.update(now)
            while (toB.isNotEmpty()) assertTrue(b.input(toB.removeFirst(), now))
            // Separate input calls matter: one cumulative batch is one fast ACK
            // event in standard KCP, rather than one event per header.
            while (toA.isNotEmpty()) assertTrue(a.input(toA.removeFirst(), now))
            now += 5
        }
        assertNotNull(repairedAt)
        assertTrue(repairedAt!! < 200)
    }

    @Test
    fun zeroWindowRecoversAfterApplicationDrains() {
        val link = Link(seed = 1, lossPercent = 0, duplicatePercent = 0, jitter = 0, window = 4)
        val messages = List(12) { payload(it, 10) }
        messages.forEach { assertTrue(link.a.send(it)) }
        link.run(250) { false }
        assertEquals(8, link.a.pendingSegments)
        assertTrue(link.receivedByB.isEmpty())
        link.run(1_000) {
            link.drain()
            link.receivedByB.size == messages.size && link.a.pendingSegments == 0
        }
        assertMessages(messages, link.receivedByB)
    }

    @Test
    fun singleSegmentSendWindowStillDeliversTheLargestFragmentedMessage() {
        // The receive budget must reserve whole MTU slots independently of the
        // send window. A budget of payload + headers would advertise zero after
        // 52 full fragments, trapping the smaller final fragment indefinitely.
        val link = Link(
            seed = 2, lossPercent = 0, duplicatePercent = 0, jitter = 0,
            sendWindow = 1, byteBudget = 128 * 1200
        )
        val message = payload(123, Kcp.MAX_MESSAGE_SIZE)
        assertTrue(link.a.send(message))
        link.run(2_000) {
            link.drain()
            link.receivedByB.size == 1 && link.a.pendingSegments == 0
        }
        assertMessages(listOf(message), link.receivedByB)
        assertEquals(0, link.a.pendingSegments)
        assertFalse(link.a.isDead)
    }

    @Test
    fun queueBackpressureIncludesEmptyMessagesAndRecoversOnAck() {
        val toA = mutableListOf<ByteArray>()
        val toB = mutableListOf<ByteArray>()
        val a = Kcp(3, mtu = 128, maxQueuedBytes = 128, output = toB::add)
        val b = Kcp(3, mtu = 128, maxQueuedBytes = 128, output = toA::add)
        repeat(5) { assertTrue(a.send(byteArrayOf())) }
        assertFalse(a.send(byteArrayOf()))
        assertFalse(a.send(ByteArray(Kcp.MAX_MESSAGE_SIZE + 1)))
        assertEquals(5, a.pendingSegments)
        a.update(0)
        toB.forEach { assertTrue(b.input(it, 1)) }
        toA.forEach { assertTrue(a.input(it, 2)) }
        repeat(5) { assertContentEquals(byteArrayOf(), b.receive()) }
        assertNull(b.receive())
        assertEquals(0, a.pendingSegments)
        assertTrue(a.send(byteArrayOf()))
    }

    @Test
    fun zeroWindowProbeRecoversWhenTheReopenNoticeIsLost() {
        val toA = ArrayDeque<ByteArray>()
        val toB = ArrayDeque<ByteArray>()
        val a = Kcp(8, receiveWindow = 1, sendWindow = 1, output = toB::addLast)
        val b = Kcp(8, receiveWindow = 1, sendWindow = 1, output = toA::addLast)
        assertTrue(a.send(byteArrayOf(1)))
        assertTrue(a.send(byteArrayOf(2)))
        a.update(0)
        while (toB.isNotEmpty()) assertTrue(b.input(toB.removeFirst(), 1))
        while (toA.isNotEmpty()) assertTrue(a.input(toA.removeFirst(), 2))
        assertContentEquals(byteArrayOf(1), b.receive())
        b.update(10)
        toA.clear() // Lose WINS. A must eventually ask again.
        var delivered: ByteArray? = null
        for (now in 10..6_000 step 10) {
            a.update(now)
            b.update(now)
            while (toB.isNotEmpty()) assertTrue(b.input(toB.removeFirst(), now))
            while (toA.isNotEmpty()) assertTrue(a.input(toA.removeFirst(), now))
            delivered = b.receive()
            if (delivered != null) break
        }
        assertContentEquals(byteArrayOf(2), delivered)
    }

    private class Link(
        seed: Int,
        private val lossPercent: Int,
        private val duplicatePercent: Int,
        private val jitter: Int,
        start: Int = 0,
        window: Int = 128,
        sendWindow: Int = window,
        byteBudget: Int = 64 * 1024 * 1024,
    ) {
        private data class Delivery(val due: Long, val order: Long, val toA: Boolean, val bytes: ByteArray)

        private val random = Random(seed)
        private var elapsed = 0L
        private val initial = start
        private var serial = 0L
        private val deliveries = PriorityQueue<Delivery>(compareBy<Delivery> { it.due }.thenBy { it.order })
        val a = Kcp(
            0x52575050, sendWindow = sendWindow, receiveWindow = window,
            maxQueuedBytes = byteBudget, output = { enqueue(false, it) })
        val b = Kcp(
            0x52575050, sendWindow = sendWindow, receiveWindow = window,
            maxQueuedBytes = byteBudget, output = { enqueue(true, it) })
        val receivedByA = mutableListOf<ByteArray>()
        val receivedByB = mutableListOf<ByteArray>()

        private fun enqueue(toA: Boolean, bytes: ByteArray) {
            if (random.nextInt(100) < lossPercent) return
            deliveries.add(Delivery(elapsed + 2 + random.nextInt(jitter + 1), serial++, toA, bytes))
            if (random.nextInt(100) < duplicatePercent) {
                deliveries.add(Delivery(elapsed + 2 + random.nextInt(jitter + 1), serial++, toA, bytes.copyOf()))
            }
        }

        fun run(duration: Int, done: () -> Boolean) {
            val deadline = elapsed + duration
            while (elapsed <= deadline) {
                val now = initial + elapsed.toInt()
                a.update(now)
                b.update(now)
                while (deliveries.isNotEmpty() && deliveries.peek().due <= elapsed) {
                    val delivery = deliveries.remove()
                    assertTrue((if (delivery.toA) a else b).input(delivery.bytes, now))
                }
                if (done()) return
                elapsed++
            }
        }

        fun drain() {
            while (true) receivedByA.add(a.receive() ?: break)
            while (true) receivedByB.add(b.receive() ?: break)
        }
    }

    private fun payload(sequence: Int, length: Int): ByteArray =
        ByteArray(length) { index -> (sequence * 31 + index * 17).toByte() }

    private fun assertMessages(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertContentEquals(expected[it], actual[it], "Message $it") }
    }

    private fun setInt(instance: Kcp, name: String, value: Int) {
        Kcp::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(instance, value)
    }

    private fun read32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
}
