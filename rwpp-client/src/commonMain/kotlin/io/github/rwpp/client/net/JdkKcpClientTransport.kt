/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.client.net

import io.github.rwpp.api.net.kcp.Kcp
import io.github.rwpp.api.net.transport.*
import java.net.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** JDK UDP client driven by the shared message-oriented KCP implementation. */
class JdkKcpClientTransport internal constructor(
    private val config: TransportConfig,
    private val host: String,
    private val messages: TransportMessages,
    private val backend: JdkTransportBackend,
) : ClientTransport {
    private val lock = Object()
    private var socket: DatagramSocket? = null
    private var protocol: Kcp? = null
    private var sink: ClientSink? = null
    private var callbacks: OrderedPacketExecutor? = null
    private var receiver: Thread? = null
    private var kcpTimer: ScheduledFuture<*>? = null
    private var idleTimer: ScheduledFuture<*>? = null
    private var kcpGeneration = 0L
    private var idleGeneration = 0L
    private var lastInboundMillis = 0L
    private var connectAttempted = false
    private var active = false
    private var closed = false
    private var terminal = false

    override fun connect(sink: ClientSink): ClientLink {
        val connection = DatagramSocket()
        try {
            synchronized(lock) {
                check(!closed) { messages.connectionClosed("KCP") }
                check(!connectAttempted) { "A KCP client transport can connect only once" }
                connectAttempted = true
                socket = connection
            }
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
        }

        try {
            connection.connect(InetSocketAddress(host, config.port))
            connection.receiveBufferSize = maxOf(64 * 1024, config.mtu * config.readQueueDatagrams)
            connection.sendBufferSize = maxOf(64 * 1024, config.mtu * config.writeQueueMessages)
        } catch (failure: Throwable) {
            close()
            throw failure
        }

        val packetExecutor = try {
            backend.newOrderedExecutor(config.readQueueDatagrams)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        val maxQueuedBytes =
            (config.mtu.toLong() * maxOf(config.writeQueueMessages, config.readQueueDatagrams, config.receiveWindow))
                .coerceIn(config.mtu.toLong(), 64L * 1024 * 1024)
                .toInt()
        val kcp = Kcp(
            conv = config.conv,
            mtu = config.mtu,
            sendWindow = config.sendWindow,
            receiveWindow = config.receiveWindow,
            intervalMillis = config.intervalMillis,
            maxQueuedBytes = maxQueuedBytes,
            ackNoDelay = true,
        ) { datagram ->
            connection.send(DatagramPacket(datagram, datagram.size))
        }
        val readThread = Thread({ receiveLoop(connection) }, "rwpp-jdk-kcp-reader").apply { isDaemon = true }
        try {
            synchronized(lock) {
                check(!closed && !connection.isClosed) { messages.connectionClosed("KCP") }
                this.sink = sink
                callbacks = packetExecutor
                protocol = kcp
                lastInboundMillis = monotonicMillis()
                active = true
                receiver = readThread
                scheduleIdleLocked(config.idleTimeoutMillis)
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        try {
            readThread.start()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        return object : ClientLink {
            override fun send(payload: ByteArray): Boolean = sendMessage(payload)
            override fun close() = this@JdkKcpClientTransport.close()
        }
    }

    private fun sendMessage(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > config.maxPayloadBytes) return false
        var failure: Throwable? = null
        val accepted = synchronized(lock) {
            if (!active || closed || terminal) return false
            try {
                val core = checkNotNull(protocol)
                if (!core.send(payload)) return false
                val now = monotonicMillis32()
                core.update(now)
                core.flushPending(now)
                if (core.isDead) throw IllegalStateException("KCP send window exhausted")
                ensureKcpTimerLocked(core, now)
                true
            } catch (caught: Exception) {
                failure = caught
                false
            }
        }
        failure?.let { fail(messages.transportFailure("KCP", it)) }
        return accepted
    }

    private fun receiveLoop(connection: DatagramSocket) {
        val buffer = ByteArray(65_507)
        val packet = DatagramPacket(buffer, buffer.size)
        while (isActive()) {
            packet.length = buffer.size
            try {
                connection.receive(packet)
            } catch (failure: SocketException) {
                if (isActive()) fail(messages.transportFailure("KCP", failure))
                return
            } catch (failure: Exception) {
                if (isActive()) fail(messages.transportFailure("KCP", failure))
                return
            }
            if (packet.length == 0) continue
            processDatagram(buffer.copyOfRange(packet.offset, packet.offset + packet.length))
        }
    }

    private fun processDatagram(datagram: ByteArray) {
        var failure: String? = null
        synchronized(lock) {
            if (!active || closed || terminal) return
            try {
                val core = checkNotNull(protocol)
                val now = monotonicMillis32()
                if (!core.input(datagram, now)) return
                lastInboundMillis = monotonicMillis()
                scheduleIdleLocked(config.idleTimeoutMillis)
                core.update(now)
                while (true) {
                    val payload = core.receive() ?: break
                    if (payload.isEmpty() || payload.size > config.maxPayloadBytes) {
                        failure = messages.invalidPacketSize()
                        break
                    }
                    val destination = checkNotNull(sink)
                    try {
                        checkNotNull(callbacks).execute {
                            if (!isLocallyClosed()) destination.message(payload)
                        }
                    } catch (_: RuntimeException) {
                        failure = messages.inboundQueueFull()
                        break
                    }
                }
                if (core.isDead && failure == null) {
                    failure = messages.transportFailure("KCP", IllegalStateException("KCP connection is dead"))
                }
                if (failure == null) ensureKcpTimerLocked(core, now)
            } catch (caught: Exception) {
                failure = messages.transportFailure("KCP", caught)
            }
        }
        failure?.let(::fail)
    }

    private fun onKcpTimer(generation: Long) {
        var failure: String? = null
        synchronized(lock) {
            if (generation != kcpGeneration) return
            kcpTimer = null
            if (!active || closed || terminal) return
            try {
                val core = checkNotNull(protocol)
                val now = monotonicMillis32()
                core.update(now)
                if (core.isDead) {
                    failure = messages.transportFailure("KCP", IllegalStateException("KCP connection is dead"))
                } else {
                    ensureKcpTimerLocked(core, now)
                }
            } catch (caught: Exception) {
                failure = messages.transportFailure("KCP", caught)
            }
        }
        failure?.let(::fail)
    }

    private fun ensureKcpTimerLocked(core: Kcp, now: Int) {
        if (core.pendingSegments == 0) {
            cancelKcpTimerLocked()
            return
        }
        if (kcpTimer != null) return
        val deadline = core.check(now)
        val delay = (deadline - now).toLong().coerceAtLeast(1L)
        val generation = ++kcpGeneration
        kcpTimer = backend.scheduler.schedule({ onKcpTimer(generation) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelKcpTimerLocked() {
        kcpGeneration++
        kcpTimer?.cancel(false)
        kcpTimer = null
    }

    private fun scheduleIdleLocked(delayMillis: Long) {
        val generation = ++idleGeneration
        idleTimer?.cancel(false)
        idleTimer = backend.scheduler.schedule({ onIdleTimer(generation) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun onIdleTimer(generation: Long) {
        var failure: String? = null
        synchronized(lock) {
            if (generation != idleGeneration) return
            idleTimer = null
            if (!active || closed || terminal) return
            val remaining = config.idleTimeoutMillis - (monotonicMillis() - lastInboundMillis)
            if (remaining <= 0) {
                failure = messages.transportFailure("KCP", SocketTimeoutException("KCP connection idle timeout"))
            } else {
                try {
                    scheduleIdleLocked(remaining)
                } catch (caught: Exception) {
                    failure = messages.transportFailure("KCP", caught)
                }
            }
        }
        failure?.let(::fail)
    }

    private fun fail(reason: String) {
        val resources = synchronized(lock) {
            if (closed || terminal) return
            terminal = true
            active = false
            cancelKcpTimerLocked()
            idleGeneration++
            idleTimer?.cancel(false)
            idleTimer = null
            socket to (sink to callbacks)
        }
        runCatching { resources.first?.close() }
        val destination = resources.second.first ?: return
        runCatching {
            resources.second.second?.executeTerminal {
                if (!isLocallyClosed()) destination.failure(reason)
            }
        }
    }

    private fun isActive(): Boolean = synchronized(lock) { active && !closed && !terminal }
    private fun isLocallyClosed(): Boolean = synchronized(lock) { closed }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            active = false
            cancelKcpTimerLocked()
            idleGeneration++
            idleTimer?.cancel(false)
            idleTimer = null
            socket.also { socket = null } to receiver
        }
        runCatching { resources.first?.close() }
        resources.second?.interrupt()
    }

    private companion object {
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
        fun monotonicMillis32(): Int = monotonicMillis().toInt()
    }
}
