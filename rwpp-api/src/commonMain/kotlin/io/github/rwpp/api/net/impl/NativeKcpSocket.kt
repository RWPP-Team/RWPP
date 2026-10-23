package io.github.rwpp.api.net.impl

import io.github.rwpp.api.net.transport.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime switches also make comparisons use the same game hooks and application workload. */
object NativeNetworkSettings {
    val mode: String get() = System.getProperty("rwpp.network.mode", "auto").also {
        require(it in setOf("auto", "tcp", "vanilla")) { "rwpp.network.mode must be auto, tcp or vanilla" }
    }
    val probeMillis: Long get() = System.getProperty("rwpp.network.probeMillis", "1500").toLong().coerceIn(50, 1500)
    val tcpOnly = ThreadLocal.withInitial { false }
}

/** KCP first; only a matching application welcome commits the connection. TCP remains the fallback. */
class NativeKcpSocket() : Socket(), NativePacketSocket {
    private var tcp: Socket? = null
    private var transport: KcpClientTransport? = null
    private var link: ((ByteArray) -> Boolean)? = null
    private var release: (() -> Unit)? = null
    private var remote: InetSocketAddress? = null
    private var localPortValue = 0
    private val closed = AtomicBoolean()
    private val welcome = CompletableFuture<Unit>()
    private val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val incoming = ArrayDeque<Pair<Int, ByteArray>>()
    private val outgoing = ArrayDeque<Write>()
    private var inboundBytes = 0L
    private var outboundBytes = 0L
    private var outboundPackets = 0
    private var assembly: ByteArray? = null
    private var assemblyType = 0
    private var assemblyOffset = 0
    private var limit: () -> Int = { 10_000 }
    private var receiver: ((Int, ByteArray) -> Unit)? = null
    private var failure: ((String) -> Unit)? = null
    private var packetExecutor: Executor? = null
    private var pump: ScheduledFuture<*>? = null
    private var lastSendAfter = 0L
    private var closeAfterWritesRequested = false
    private var drainRecordSent = false
    private var drainAckReceived = false
    private var peerDrainReceived = false
    private var drainAckQueued = false
    private var drainAckSent = false
    private var closeTimeout: ScheduledFuture<*>? = null
    private var terminalReason: String? = null
    private var failureNotified = false
    override val transportName: String get() = (tcp as? NativePacketSocket)?.transportName ?: if (tcp != null) "TCP" else "KCP"

    override val handlesNativePackets: Boolean get() = tcp?.let { (it as? NativePacketSocket)?.handlesNativePackets ?: false } ?: true

    constructor(peer: TransportPeer, port: Int) : this() {
        remote = InetSocketAddress(peer.remoteHost ?: "127.0.0.1", 0)
        localPortValue = port
        link = peer::trySend
        release = peer::close
        welcome.complete(Unit)
    }

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 7000)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        check(link == null && tcp == null && !closed.get())
        val address = endpoint as? InetSocketAddress ?: throw SocketException("An IP endpoint is required")
        remote = address
        val started = System.nanoTime()
        val budget = timeout.takeIf { it > 0 } ?: 7000
        val attempt = KcpClientTransport(config(address.port), address.hostString)
        transport = attempt
        try {
            val connection = attempt.connect(object : ClientSink {
                override fun message(payload: ByteArray) {
                    if (!welcome.isDone) {
                        if (payload.contentEquals(control(WELCOME, nonce))) welcome.complete(Unit)
                    } else if (tcp == null) accept(payload)
                }
                override fun failure(reason: String) {
                    if (!welcome.isDone) welcome.completeExceptionally(IOException(reason))
                    else if (tcp == null) fail(reason)
                }
            })
            link = connection::send
            release = connection::close
            check(connection.send(control(HELLO, nonce))) { "KCP hello rejected" }
            welcome.get(minOf(NativeNetworkSettings.probeMillis, budget.toLong()), TimeUnit.MILLISECONDS)
            startPump()
        } catch (_: Exception) {
            // Publish the replacement before closing the failed attempt: its late callbacks are stale.
            val replacement = TransportRuntime.backend.nativeClientSocket(true)
            tcp = replacement
            link = null; release = null; transport = null
            attempt.close()
            val remaining = (budget - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).coerceAtLeast(1).toInt()
            replacement.connect(endpoint, remaining)
        }
    }

    override fun startReceiving(limit: () -> Int, receive: (Int, ByteArray) -> Unit, failure: (String) -> Unit) {
        tcp?.let { (it as NativePacketSocket).startReceiving(limit, receive, failure); return }
        synchronized(this) {
            check(receiver == null)
            this.limit = limit; receiver = receive; this.failure = failure
            packetExecutor = TransportRuntime.backend.packetExecutor()
        }
        queueDrainAckIfReady()
        startPump()
    }

    @Synchronized fun accept(payload: ByteArray) {
        if (closed.get()) return
        try {
            require(payload.isNotEmpty())
            val input = ByteBuffer.wrap(payload)
            when (input.get().toInt()) {
                START -> {
                    check(!peerDrainReceived) { "KCP data arrived after drain" }
                    check(assembly == null && input.remaining() >= 8)
                    assemblyType = input.int
                    val length = input.int
                    require(length in 0..limit()) { "Native KCP packet length exceeds limit: $length" }
                    check(inboundBytes + length <= QUEUE_BYTES) { "Native KCP receive queue full" }
                    assembly = ByteArray(length); assemblyOffset = 0
                    append(input)
                }
                CONTINUE -> { check(!peerDrainReceived) { "KCP data arrived after drain" }; check(assembly != null); append(input) }
                CLOSE -> { require(!input.hasRemaining()); fail("KCP connection closed") }
                DRAIN -> {
                    require(!input.hasRemaining())
                    require(assembly == null) { "KCP drain arrived before packet completion" }
                    check(!peerDrainReceived) { "Duplicate KCP drain" }
                    peerDrainReceived = true
                    armCloseTimeout()
                    queueDrainAckIfReady()
                }
                DRAIN_ACK -> {
                    require(!input.hasRemaining())
                    check(closeAfterWritesRequested && drainRecordSent && !drainAckReceived) {
                        "Unexpected KCP drain acknowledgement"
                    }
                    drainAckReceived = true
                    finishDrainIfReady()
                }
                else -> error("Invalid native KCP record")
            }
        } catch (problem: Exception) { fail(problem.message ?: "Invalid KCP packet") }
    }

    private fun append(input: ByteBuffer) {
        val bytes = checkNotNull(assembly)
        val size = input.remaining()
        require(size <= bytes.size - assemblyOffset)
        input.get(bytes, assemblyOffset, size)
        assemblyOffset += size
        if (assemblyOffset == bytes.size) {
            check(incoming.size < 512 && inboundBytes + bytes.size <= QUEUE_BYTES) { "Native KCP receive queue full" }
            incoming.add(assemblyType to bytes); inboundBytes += bytes.size
            assembly = null
            if (receiver != null) startPump()
        }
    }

    @Synchronized override fun send(type: Int, bytes: ByteArray, delayAfterMillis: Int): Boolean {
        tcp?.let { return (it as NativePacketSocket).send(type, bytes, delayAfterMillis) }
        if (closed.get() || closeAfterWritesRequested || drainAckQueued || link == null) return false
        if (bytes.size > NativePacketSocket.MAX_PACKET_BYTES || outboundPackets >= 512 || outboundBytes + bytes.size > QUEUE_BYTES) {
            fail("Native KCP send queue full"); return false
        }
        val due = maxOf(System.nanoTime(), lastSendAfter)
        outgoing.add(Write(type, bytes, due))
        outboundPackets++
        outboundBytes += bytes.size
        lastSendAfter = due + TimeUnit.MILLISECONDS.toNanos(delayAfterMillis.coerceAtLeast(0).toLong())
        startPump()
        return true
    }

    @Synchronized private fun startPump(delayMillis: Long = 0) {
        if (pump != null) return
        if (closed.get() && (receiver == null || incoming.isEmpty())) {
            notifyFailureIfDrained()
            return
        }
        pump = TransportRuntime.backend.scheduler.schedule({
            synchronized(this) { pump = null }
            drain()
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        synchronized(this) {
            // A single ordered worker task drains a bounded batch; no per-message polling threads.
            if (receiver != null && incoming.isNotEmpty() && deliveryScheduled.compareAndSet(false, true)) {
                try {
                    packetExecutor!!.execute {
                        try {
                            repeat(64) {
                                val frame = synchronized(this) { incoming.pollFirst() } ?: return@execute
                                try { receiver!!(frame.first, frame.second) }
                                finally { synchronized(this) { inboundBytes -= frame.second.size } }
                            }
                        } catch (problem: Exception) { fail(problem.message ?: "Native KCP receive failed") }
                        finally {
                            deliveryScheduled.set(false)
                            synchronized(this) {
                                if (incoming.isNotEmpty()) startPump()
                                else if (closed.get()) notifyFailureIfDrained()
                                else queueDrainAckIfReady()
                            }
                        }
                    }
                } catch (_: RuntimeException) { deliveryScheduled.set(false); fail("Native KCP worker queue full") }
            }
            if (closed.get()) {
                notifyFailureIfDrained()
                return
            }
            var sent = 0
            while (sent++ < 16 && outgoing.isNotEmpty()) {
                val write = outgoing.peekFirst()
                if (System.nanoTime() < write.due) { startPump(1); return }
                val message = write.pending ?: write.next().also { write.pending = it }
                if (link?.invoke(message) != true) { startPump(2); return }
                write.pending = null
                if (write.controlRecord != null) {
                    outgoing.removeFirst()
                    when (write.controlRecord) {
                        DRAIN -> drainRecordSent = true
                        DRAIN_ACK -> drainAckSent = true
                    }
                    finishDrainIfReady()
                } else {
                    write.offset += message.size - if (write.first) 9 else 1
                    write.first = false
                    if (write.offset == write.bytes.size) {
                        outgoing.removeFirst()
                        outboundBytes -= write.bytes.size
                        outboundPackets--
                    }
                }
            }
            if (outgoing.isNotEmpty()) startPump()
        }
    }

    private val deliveryScheduled = AtomicBoolean()
    @Synchronized override fun closeAfterWrites() {
        tcp?.let { (it as NativePacketSocket).closeAfterWrites(); return }
        if (closed.get() || closeAfterWritesRequested) return
        closeAfterWritesRequested = true
        outgoing.addLast(Write.control(DRAIN, lastSendAfter))
        armCloseTimeout()
        startPump()
    }

    @Synchronized private fun queueDrainAckIfReady() {
        if (closed.get() || !peerDrainReceived || drainAckQueued || receiver == null ||
            incoming.isNotEmpty() || deliveryScheduled.get()) return
        drainAckQueued = true
        outgoing.addLast(Write.control(DRAIN_ACK, maxOf(System.nanoTime(), lastSendAfter)))
        startPump()
    }

    /** Start one bounded wait only while a drain exchange still needs confirmation. */
    @Synchronized private fun armCloseTimeout() {
        if (closed.get() || closeTimeout != null || !closeConfirmationPending()) return
        closeTimeout = TransportRuntime.backend.scheduler.schedule({
            synchronized(this) {
                closeTimeout = null
                if (!closed.get() && closeConfirmationPending()) {
                    fail("KCP close confirmation timed out after 5 seconds")
                }
            }
        }, 5, TimeUnit.SECONDS)
    }

    private fun closeConfirmationPending(): Boolean =
        (closeAfterWritesRequested && !(drainRecordSent && drainAckReceived && (!peerDrainReceived || drainAckSent))) ||
            (peerDrainReceived && !drainAckSent)

    /** Complete only after our DRAIN has been acknowledged and any peer DRAIN has been answered. */
    @Synchronized private fun finishDrainIfReady() {
        if (closed.get()) return
        if (closeAfterWritesRequested && drainRecordSent && drainAckReceived && (!peerDrainReceived || drainAckSent)) {
            closeTimeout?.cancel(false); closeTimeout = null
            close()
        } else if (peerDrainReceived && drainAckSent && !closeAfterWritesRequested) {
            closeTimeout?.cancel(false); closeTimeout = null
        } else {
            armCloseTimeout()
        }
    }

    /** Deliver terminal notification on the ordered packet executor after accepted frames. */
    @Synchronized private fun notifyFailureIfDrained() {
        val reason = terminalReason ?: return
        val callback = failure ?: return
        val executor = packetExecutor ?: return
        if (!closed.get() || incoming.isNotEmpty() || deliveryScheduled.get() || failureNotified) return
        failureNotified = true
        try { executor.execute { callback(reason) } }
        catch (_: RuntimeException) { callback(reason) }
    }

    @Synchronized fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        terminalReason = reason
        if (!welcome.isDone) welcome.completeExceptionally(IOException(reason))
        closeTimeout?.cancel(false); closeTimeout = null
        outgoing.clear(); outboundBytes = 0; outboundPackets = 0; assembly = null
        val cleanup = release
        val owned = transport
        // Backend close is nonblocking and safe from its own callback; the API owns no threads.
        val dispose = Runnable { cleanup?.invoke(); owned?.close() }
        try { TransportRuntime.backend.scheduler.execute(dispose) }
        catch (_: RuntimeException) { dispose.run() }
        if (receiver != null && incoming.isNotEmpty()) startPump()
        else notifyFailureIfDrained()
    }

    override fun close() {
        tcp?.let { it.close(); return }
        runCatching { link?.invoke(byteArrayOf(CLOSE.toByte())) }
        fail("KCP connection closed")
    }
    override fun isClosed(): Boolean = tcp?.isClosed ?: closed.get()
    override fun isConnected(): Boolean = tcp?.isConnected ?: (welcome.isDone && !welcome.isCompletedExceptionally)
    override fun isBound(): Boolean = tcp?.isBound ?: isConnected
    override fun getInetAddress(): InetAddress? = tcp?.inetAddress ?: remote?.address
    override fun getPort(): Int = tcp?.port ?: remote?.port ?: 0
    override fun getLocalPort(): Int = tcp?.localPort ?: localPortValue
    override fun getRemoteSocketAddress(): SocketAddress? = tcp?.remoteSocketAddress ?: remote

    override fun getInputStream(): java.io.InputStream = tcp?.getInputStream() ?: throw SocketException("KCP uses native packet framing")
    override fun getOutputStream(): java.io.OutputStream = tcp?.getOutputStream() ?: throw SocketException("KCP uses native packet framing")
    private var readTimeout = 0
    override fun setSoTimeout(value: Int) { require(value >= 0); readTimeout = value; tcp?.soTimeout = value }
    override fun getSoTimeout(): Int = tcp?.soTimeout ?: readTimeout
    override fun setTcpNoDelay(value: Boolean) { tcp?.tcpNoDelay = value }
    override fun getTcpNoDelay(): Boolean = tcp?.tcpNoDelay ?: true

    private class Write(
        val type: Int,
        val bytes: ByteArray,
        val due: Long,
        val controlRecord: Int? = null,
    ) {
        val control = controlRecord != null
        var first = !control; var offset = 0; var pending: ByteArray? = null
        fun next(): ByteArray {
            if (control) return bytes
            val size = minOf(CHUNK_BYTES, bytes.size - offset)
            return ByteBuffer.allocate(size + if (first) 9 else 1).apply {
                put((if (first) START else CONTINUE).toByte())
                if (first) { putInt(type); putInt(bytes.size) }
                put(bytes, offset, size)
            }.array()
        }
        companion object {
            fun control(record: Int, due: Long) = Write(0, byteArrayOf(record.toByte()), due, record)
        }
    }

    companion object {
        private const val START = 1
        private const val CONTINUE = 2
        private const val CLOSE = 3
        private const val DRAIN = 4
        private const val DRAIN_ACK = 5
        private const val CHUNK_BYTES = 16 * 1024
        private const val QUEUE_BYTES = 64L * 1024 * 1024
        private val HELLO = byteArrayOf(82, 87, 80, 80, 75, 67, 80, 1)
        private val WELCOME = byteArrayOf(82, 87, 80, 80, 75, 67, 80, 2)
        fun helloNonce(bytes: ByteArray): ByteArray? =
            bytes.takeIf { it.size == 24 && it.copyOfRange(0, 8).contentEquals(HELLO) }?.copyOfRange(8, 24)
        fun welcome(nonce: ByteArray) = control(WELCOME, nonce)
        private fun control(prefix: ByteArray, nonce: ByteArray) = prefix + nonce
        fun config(port: Int) = TransportConfig(bindHost = "0.0.0.0", port = port,
            conv = 0x52575050, maxPayloadBytes = 60 * 1024, messageThreads = 4)
    }
}

