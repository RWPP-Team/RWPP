/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.client.net

import io.github.rwpp.api.net.transport.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*

/** JDK TCP client using the game's four-byte big-endian length prefix. */
class JdkTcpClientTransport internal constructor(
    private val config: TransportConfig,
    private val host: String,
    private val connectTimeoutMillis: Int,
    private val messages: TransportMessages,
    private val backend: JdkTransportBackend,
) : ClientTransport {
    private val lock = Object()
    private val outgoing = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val maxQueuedBytes = maxOf(config.maxTcpPayloadBytes, config.mtu * config.writeQueueMessages)

    private var socket: Socket? = null
    private var sink: ClientSink? = null
    private var callbacks: OrderedPacketExecutor? = null
    private var reader: Thread? = null
    private var writer: Thread? = null
    private var connectAttempted = false
    private var active = false
    private var closed = false
    private var terminal = false

    override fun connect(sink: ClientSink): ClientLink {
        val connection = Socket()
        try {
            synchronized(lock) {
                check(!closed) { messages.connectionClosed("TCP") }
                check(!connectAttempted) { "A TCP client transport can connect only once" }
                connectAttempted = true
                socket = connection
            }
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
        }

        try {
            connection.tcpNoDelay = true
            connection.connect(InetSocketAddress(host, config.port), connectTimeoutMillis.coerceAtLeast(0))
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
        val readThread = Thread({ readLoop(connection) }, "rwpp-jdk-tcp-reader").apply { isDaemon = true }
        val writeThread = Thread({ writeLoop(connection) }, "rwpp-jdk-tcp-writer").apply { isDaemon = true }
        try {
            synchronized(lock) {
                check(!closed && !connection.isClosed) { messages.connectionClosed("TCP") }
                this.sink = sink
                callbacks = packetExecutor
                active = true
                reader = readThread
                writer = writeThread
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        try {
            readThread.start()
            writeThread.start()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        return object : ClientLink {
            override fun send(payload: ByteArray): Boolean = enqueue(payload)
            override fun close() = this@JdkTcpClientTransport.close()
        }
    }

    private fun enqueue(payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > config.maxTcpPayloadBytes) return false
        synchronized(lock) {
            if (!active || closed || terminal || outgoing.size >= config.writeQueueMessages ||
                payload.size > maxQueuedBytes - queuedBytes
            ) return false
            outgoing.addLast(payload.copyOf())
            queuedBytes += payload.size
            lock.notifyAll()
            return true
        }
    }

    private fun readLoop(connection: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(connection.getInputStream()))
            while (isActive()) {
                val size = try {
                    input.readInt()
                } catch (_: EOFException) {
                    fail(messages.connectionClosed("TCP"))
                    return
                }
                if (size !in 1..config.maxPayloadBytes) {
                    fail(messages.invalidPacketSize())
                    return
                }
                val payload = ByteArray(size)
                input.readFully(payload)
                val destination = synchronized(lock) { sink }
                    ?: return
                try {
                    callbacks?.execute {
                        if (!isLocallyClosed()) destination.message(payload)
                    } ?: return
                } catch (_: RuntimeException) {
                    fail(messages.inboundQueueFull())
                    return
                }
            }
        } catch (failure: IOException) {
            if (isActive()) fail(messages.transportFailure("TCP", failure))
        } catch (failure: RuntimeException) {
            if (isActive()) fail(messages.transportFailure("TCP", failure))
        }
    }

    private fun writeLoop(connection: Socket) {
        try {
            val output = DataOutputStream(BufferedOutputStream(connection.getOutputStream()))
            while (true) {
                val payload = synchronized(lock) {
                    while (active && outgoing.isEmpty()) lock.wait()
                    if (!active || closed || terminal) return
                    outgoing.removeFirst().also { queuedBytes -= it.size }
                }
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: IOException) {
            if (isActive()) fail(messages.transportFailure("TCP", failure))
        } catch (failure: RuntimeException) {
            if (isActive()) fail(messages.transportFailure("TCP", failure))
        }
    }

    private fun fail(reason: String) {
        val resources = synchronized(lock) {
            if (closed || terminal) return
            terminal = true
            active = false
            outgoing.clear()
            queuedBytes = 0
            lock.notifyAll()
            Triple(socket, sink, callbacks)
        }
        runCatching { resources.first?.close() }
        val destination = resources.second ?: return
        runCatching {
            resources.third?.executeTerminal {
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
            outgoing.clear()
            queuedBytes = 0
            lock.notifyAll()
            Triple(socket.also { socket = null }, reader, writer)
        }
        runCatching { resources.first?.close() }
        resources.second?.interrupt()
        resources.third?.interrupt()
    }
}
