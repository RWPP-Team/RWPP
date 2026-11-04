/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.api.net.kcp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Message-oriented KCP, using nodelay, fast-resend 2 and no congestion window.
 *
 * This class owns neither threads nor sockets. Confine an instance to one caller
 * or serialize all calls, including callbacks. Do not reenter it from [output].
 * Timestamps are monotonic milliseconds truncated to 32 bits; subtraction is
 * deliberately signed so clocks and sequence numbers can wrap around.
 */
class Kcp(
    val conv: Int,
    val mtu: Int = 1200,
    private val sendWindow: Int = 128,
    private val receiveWindow: Int = 128,
    private val intervalMillis: Int = 10,
    private val maxQueuedBytes: Int = 64 * 1024 * 1024,
    private val ackNoDelay: Boolean = true,
    private val output: (ByteArray) -> Unit,
) {
    private class Segment(val data: ByteArray, val fragment: Int, var sequence: Int = 0) {
        var timestamp = 0
        var resendAt = 0
        var rto = RTO_DEFAULT
        var fastAck = 0
        var transmissions = 0
        val cost: Int get() = data.size + HEADER_SIZE
    }

    private data class Header(
        val command: Int,
        val fragment: Int,
        val window: Int,
        val timestamp: Int,
        val sequence: Int,
        val unacknowledged: Int,
        val offset: Int,
        val length: Int,
    )

    private val mss = mtu - HEADER_SIZE
    private val sendQueue = ArrayDeque<Segment>()
    private val sendBuffer = ArrayList<Segment>()
    private val receiveQueue = ArrayDeque<Segment>()
    private val receiveBuffer = ArrayList<Segment>()
    private val acknowledgements = linkedMapOf<Int, Int>()
    private var queuedSendBytes = 0
    private var queuedReceiveBytes = 0
    private var sendNext = 0
    private var sendUnacknowledged = 0
    private var receiveNext = 0
    private var remoteWindow = receiveWindow
    private var smoothedRtt = 0
    private var rttVariance = 0
    private var receiveRto = RTO_DEFAULT
    private var updated = false
    private var flushAt = 0
    private var probeAt = 0
    private var probeWait = 0
    private var askWindow = false
    private var tellWindow = false

    var isDead: Boolean = false
        private set

    /** Includes queued and unacknowledged fragments, rather than messages. */
    val pendingSegments: Int get() = sendQueue.size + sendBuffer.size

    init {
        require(mtu in (HEADER_SIZE + 1)..65_507)
        require(sendWindow in 1..65_535 && receiveWindow in 1..65_535)
        require(intervalMillis in 1..5_000)
        require(maxQueuedBytes >= mtu)
    }

    /** Copies the message. False means backpressure, an oversized message or a dead link. */
    fun send(payload: ByteArray): Boolean {
        if (isDead || payload.size > MAX_MESSAGE_SIZE) return false
        val count = max(1, (payload.size + mss - 1) / mss)
        if (count >= MAX_FRAGMENTS || count > receiveWindow) return false
        val cost = payload.size.toLong() + count.toLong() * HEADER_SIZE
        if (cost > maxQueuedBytes.toLong() - queuedSendBytes) return false
        var offset = 0
        repeat(count) { index ->
            val length = min(mss, payload.size - offset)
            sendQueue.addLast(Segment(payload.copyOfRange(offset, offset + length), count - index - 1))
            offset += length
        }
        queuedSendBytes += cost.toInt()
        return true
    }

    /**
     * Validates the complete datagram before changing state. Malformed input
     * returns false; valid data outside the receive window is ignored, as in KCP.
     */
    fun input(datagram: ByteArray, now: Int): Boolean {
        if (isDead) return false
        val headers = validate(datagram) ?: return false
        var greatestAck: Header? = null
        for (header in headers) {
            remoteWindow = header.window
            parseUnacknowledged(header.unacknowledged)
            when (header.command) {
                ACK -> {
                    if (now - header.timestamp >= 0) updateRtt(now - header.timestamp)
                    parseAck(header.sequence)
                    val previous = greatestAck
                    if (previous == null || (header.sequence - previous.sequence > 0 &&
                                header.timestamp - previous.timestamp >= 0)
                    ) greatestAck = header
                }

                PUSH -> {
                    if (header.sequence - (receiveNext + receiveWindow) < 0) {
                        if (header.sequence - receiveNext < 0) {
                            acknowledge(header.sequence, header.timestamp, now)
                        } else {
                            val index = insertionIndex(header.sequence)
                            val duplicate = index < receiveBuffer.size &&
                                    receiveBuffer[index].sequence == header.sequence
                            val cost = header.length + HEADER_SIZE
                            if (duplicate || queuedReceiveBytes <= maxQueuedBytes - cost) {
                                acknowledge(header.sequence, header.timestamp, now)
                                if (!duplicate) {
                                    receiveBuffer.add(
                                        index, Segment(
                                            datagram.copyOfRange(
                                                header.offset, header.offset + header.length,
                                            ), header.fragment, header.sequence
                                        )
                                    )
                                    queuedReceiveBytes += cost
                                }
                            }
                        }
                    }
                }

                ASK -> tellWindow = true
                TELL -> Unit
            }
        }
        greatestAck?.takeIf { it.sequence - sendNext < 0 }?.let { ack ->
            for (segment in sendBuffer) {
                if (ack.sequence - segment.sequence <= 0) break
                if (ack.timestamp - segment.timestamp >= 0) segment.fastAck++
            }
        }
        moveReceiveBuffer()
        if (ackNoDelay && acknowledgements.isNotEmpty()) flushAcknowledgements(now)
        return true
    }

    /** Calls the datagram callback for ACKs, probes, new data and retransmissions. */
    fun update(now: Int) {
        if (isDead) return
        if (!updated) {
            updated = true
            flushAt = now
        }
        var elapsed = now - flushAt
        if (elapsed >= 10_000 || elapsed < -10_000) {
            flushAt = now
            elapsed = 0
        }
        if (elapsed < 0) return
        flushAt += intervalMillis
        if (now - flushAt >= 0) flushAt = now + intervalMillis
        flush(now)
    }

    /**
     * Flushes at the end of a caller-owned batch, without waiting for the timer.
     * Keep calling [update] on the schedule from [check] for retransmission and
     * window probes when there is no application traffic.
     */
    fun flushPending(now: Int) {
        if (isDead) return
        if (!updated) {
            updated = true
            flushAt = now + intervalMillis
        }
        flush(now)
    }

    /** Absolute timestamp for the next update, assuming there is no new input/send. */
    fun check(now: Int): Int {
        if (!updated || isDead) return now
        val delta = now - flushAt
        if (delta >= 10_000 || delta < -10_000 || delta >= 0) return now
        var wait = min(flushAt - now, intervalMillis)
        for (segment in sendBuffer) {
            val due = segment.resendAt - now
            if (due <= 0) return now
            wait = min(wait, due)
        }
        return now + wait
    }

    /** Returns one complete message, in order, once only. */
    fun receive(): ByteArray? {
        val first = receiveQueue.firstOrNull() ?: return null
        val count = first.fragment + 1
        if (receiveQueue.size < count) return null
        var length = 0
        for (index in 0 until count) {
            val fragment = receiveQueue[index]
            if (fragment.fragment != count - index - 1 ||
                length > MAX_MESSAGE_SIZE - fragment.data.size
            ) {
                isDead = true
                return null
            }
            length += fragment.data.size
        }
        val wasFull = receiveQueue.size >= receiveWindow || queuedReceiveBytes >= maxQueuedBytes - mtu
        val message = ByteArray(length)
        var offset = 0
        repeat(count) {
            val segment = receiveQueue.removeFirst()
            segment.data.copyInto(message, offset)
            offset += segment.data.size
            queuedReceiveBytes -= segment.cost
        }
        moveReceiveBuffer()
        if (wasFull) tellWindow = true
        return message
    }

    private fun validate(datagram: ByteArray): List<Header>? {
        if (datagram.size !in HEADER_SIZE..65_535) return null
        val headers = ArrayList<Header>()
        var offset = 0
        while (offset < datagram.size) {
            if (datagram.size - offset < HEADER_SIZE || read32(datagram, offset) != conv) return null
            val command = datagram[offset + 4].toInt() and 255
            val fragment = datagram[offset + 5].toInt() and 255
            val length = read32(datagram, offset + 20)
            if (command !in PUSH..TELL || length < 0 || length > datagram.size - offset - HEADER_SIZE) return null
            if (command == PUSH) {
                // The sender's MTU need not equal ours. Local MSS constrains
                // output only; accepting a larger peer segment is standard KCP.
                if (fragment >= MAX_FRAGMENTS - 1 || fragment >= receiveWindow || length > MAX_MESSAGE_SIZE) return null
            } else if (length != 0 || fragment != 0) return null
            headers.add(
                Header(
                    command, fragment, read16(datagram, offset + 6),
                    read32(datagram, offset + 8), read32(datagram, offset + 12),
                    read32(datagram, offset + 16), offset + HEADER_SIZE, length
                )
            )
            offset += HEADER_SIZE + length
        }
        return headers
    }

    private fun parseUnacknowledged(sequence: Int) {
        // Never let a forged/stale UNA remove unsent data past the current edge.
        if (sequence - sendNext > 0) return
        var count = 0
        while (count < sendBuffer.size && sequence - sendBuffer[count].sequence > 0) {
            queuedSendBytes -= sendBuffer[count].cost
            count++
        }
        if (count > 0) sendBuffer.subList(0, count).clear()
        shrinkSendBuffer()
    }

    private fun parseAck(sequence: Int) {
        if (sequence - sendUnacknowledged < 0 || sequence - sendNext >= 0) return
        for (index in sendBuffer.indices) {
            val segment = sendBuffer[index]
            if (segment.sequence == sequence) {
                queuedSendBytes -= segment.cost
                sendBuffer.removeAt(index)
                break
            }
            if (sequence - segment.sequence < 0) break
        }
        shrinkSendBuffer()
    }

    private fun shrinkSendBuffer() {
        sendUnacknowledged = sendBuffer.firstOrNull()?.sequence ?: sendNext
    }

    private fun updateRtt(rtt: Int) {
        // A peer-controlled echo must not overflow the RTT estimator.
        val sample = min(rtt, RTO_MAX)
        if (smoothedRtt == 0) {
            smoothedRtt = sample
            rttVariance = sample / 2
        } else {
            rttVariance = (3 * rttVariance + abs(sample - smoothedRtt)) / 4
            smoothedRtt = max(1, (7 * smoothedRtt + sample) / 8)
        }
        receiveRto = (smoothedRtt + max(intervalMillis, 4 * rttVariance)).coerceIn(RTO_MIN, RTO_MAX)
    }

    private fun insertionIndex(sequence: Int): Int {
        var low = 0
        var high = receiveBuffer.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (receiveBuffer[middle].sequence - sequence < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private fun moveReceiveBuffer() {
        var count = 0
        while (count < receiveBuffer.size && receiveBuffer[count].sequence == receiveNext &&
            receiveQueue.size < receiveWindow
        ) {
            receiveQueue.addLast(receiveBuffer[count])
            receiveNext++
            count++
        }
        if (count > 0) receiveBuffer.subList(0, count).clear()
    }

    private fun acknowledge(sequence: Int, timestamp: Int, now: Int) {
        if (acknowledgements.size >= receiveWindow * 2) flushAcknowledgements(now)
        acknowledgements[sequence] = timestamp
    }

    private fun unusedWindow(): Int {
        // Reserve storage for every advertised segment, including empty ones.
        val byteSlots = max(0, (maxQueuedBytes - queuedReceiveBytes) / mtu)
        return min(receiveWindow - receiveQueue.size, byteSlots)
    }

    private fun flushAcknowledgements(now: Int) {
        val writer = Writer()
        for ((sequence, timestamp) in acknowledgements) writer.append(ACK, 0, timestamp, sequence)
        acknowledgements.clear()
        if (tellWindow) {
            writer.append(TELL, 0, now, 0)
            tellWindow = false
        }
        writer.finish()
    }

    private fun flush(now: Int) {
        val writer = Writer()
        for ((sequence, timestamp) in acknowledgements) writer.append(ACK, 0, timestamp, sequence)
        acknowledgements.clear()
        if (remoteWindow == 0) {
            if (probeWait == 0) {
                probeWait = PROBE_INITIAL
                probeAt = now + probeWait
            } else if (now - probeAt >= 0) {
                probeWait = min(PROBE_LIMIT, max(probeWait, PROBE_INITIAL) + probeWait / 2)
                probeAt = now + probeWait
                askWindow = true
            }
        } else {
            probeWait = 0
            probeAt = 0
        }
        if (askWindow) writer.append(ASK, 0, now, 0)
        if (tellWindow) writer.append(TELL, 0, now, 0)
        askWindow = false
        tellWindow = false
        val window = min(sendWindow, remoteWindow)
        while (sendQueue.isNotEmpty() && sendNext - (sendUnacknowledged + window) < 0) {
            val segment = sendQueue.removeFirst()
            segment.sequence = sendNext++
            sendBuffer.add(segment)
        }
        for (segment in sendBuffer) {
            val needsSend = when {
                segment.transmissions == 0 -> {
                    segment.rto = receiveRto
                    segment.resendAt = now + segment.rto
                    true
                }

                now - segment.resendAt >= 0 -> {
                    segment.rto = min(RTO_MAX, segment.rto + segment.rto / 2)
                    segment.resendAt = now + segment.rto
                    segment.fastAck = 0
                    true
                }

                segment.fastAck >= 2 && segment.transmissions <= 5 -> {
                    segment.fastAck = 0
                    segment.resendAt = now + segment.rto
                    true
                }

                else -> false
            }
            if (needsSend) {
                segment.transmissions++
                segment.timestamp = now
                writer.append(PUSH, segment.fragment, now, segment.sequence, segment.data)
                if (segment.transmissions >= DEAD_LINK) isDead = true
            }
        }
        writer.finish()
    }

    /** One MTU scratch buffer; each callback receives its own immutable-by-us copy. */
    private inner class Writer {
        private var bytes: ByteArray? = null
        private var offset = 0

        fun append(command: Int, fragment: Int, timestamp: Int, sequence: Int, data: ByteArray? = null) {
            val length = data?.size ?: 0
            if (offset + HEADER_SIZE + length > mtu) finish()
            val bytes = this.bytes ?: ByteArray(mtu).also { this.bytes = it }
            write32(bytes, offset, conv)
            bytes[offset + 4] = command.toByte()
            bytes[offset + 5] = fragment.toByte()
            write16(bytes, offset + 6, unusedWindow())
            write32(bytes, offset + 8, timestamp)
            write32(bytes, offset + 12, sequence)
            write32(bytes, offset + 16, receiveNext)
            write32(bytes, offset + 20, length)
            if (data != null) data.copyInto(bytes, offset + HEADER_SIZE)
            offset += HEADER_SIZE + length
        }

        fun finish() {
            if (offset > 0) {
                val datagram = checkNotNull(bytes).copyOf(offset)
                offset = 0
                output(datagram)
            }
        }
    }

    companion object {
        const val HEADER_SIZE: Int = 24
        const val MAX_MESSAGE_SIZE: Int = 60 * 1024
        private const val MAX_FRAGMENTS = 128
        private const val PUSH = 81
        private const val ACK = 82
        private const val ASK = 83
        private const val TELL = 84
        private const val RTO_MIN = 30
        private const val RTO_DEFAULT = 200
        private const val RTO_MAX = 60_000
        private const val PROBE_INITIAL = 5_000
        private const val PROBE_LIMIT = 120_000
        private const val DEAD_LINK = 20

        private fun read16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

        private fun read32(bytes: ByteArray, offset: Int): Int =
            read16(bytes, offset) or (read16(bytes, offset + 2) shl 16)

        private fun write16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }

        private fun write32(bytes: ByteArray, offset: Int, value: Int) {
            write16(bytes, offset, value)
            write16(bytes, offset + 2, value ushr 16)
        }
    }
}
