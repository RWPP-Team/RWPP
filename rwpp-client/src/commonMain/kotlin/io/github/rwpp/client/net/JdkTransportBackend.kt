/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.client.net

import io.github.rwpp.api.net.impl.NativeKcpSocket
import io.github.rwpp.api.net.transport.*
import java.net.Socket
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Client-side transport backend. Network I/O uses the JDK; only callbacks use shared pools. */
class JdkTransportBackend : TransportBackend {
    private val closed = AtomicBoolean()
    private val threadIds = AtomicInteger()
    private val threadFactory = ThreadFactory { task ->
        Thread(task, "rwpp-jdk-network-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }

    private val schedulerPool = ScheduledThreadPoolExecutor(2, threadFactory).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    private val callbackPool = ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4096),
        threadFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val clients = ConcurrentHashMap.newKeySet<ManagedClientTransport>()
    private val lifecycleLock = Any()

    override val scheduler: ScheduledExecutorService
        get() {
            check(!closed.get()) { "JDK transport backend is closed" }
            return schedulerPool
        }

    override fun packetExecutor(): Executor = newOrderedExecutor()

    internal fun newOrderedExecutor(queueLimit: Int = CALLBACK_QUEUE_LIMIT): OrderedPacketExecutor {
        check(!closed.get()) { "JDK transport backend is closed" }
        return OrderedPacketExecutor(callbackPool, queueLimit)
    }

    override fun nativeClientSocket(tcpOnly: Boolean): Socket =
        if (tcpOnly) Socket() else NativeKcpSocket()

    override fun kcpClient(config: TransportConfig, host: String, messages: TransportMessages): ClientTransport =
        track { JdkKcpClientTransport(config, host, messages, this) }

    override fun tcpClient(
        config: TransportConfig,
        host: String,
        timeout: Int,
        messages: TransportMessages,
    ): ClientTransport = track { JdkTcpClientTransport(config, host, timeout, messages, this) }

    private fun track(factory: () -> ClientTransport): ClientTransport {
        val managed = ManagedClientTransport(factory)
        synchronized(lifecycleLock) {
            check(!closed.get()) { "JDK transport backend is closed" }
            clients.add(managed)
        }
        return managed
    }

    override fun close() {
        val active = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            clients.toList().also { clients.clear() }
        }
        active.forEach { runCatching { it.close() } }
        schedulerPool.shutdownNow()
        callbackPool.shutdownNow()
    }

    private inner class ManagedClientTransport(
        private val factory: () -> ClientTransport,
    ) : ClientTransport {
        private val lock = Any()
        private var active: Connection? = null
        private var permanentlyClosed = false

        override fun connect(sink: ClientSink): ClientLink {
            val delegate = factory()
            val connection = Connection(delegate)
            try {
                synchronized(lifecycleLock) {
                    check(!closed.get()) { "JDK transport backend is closed" }
                    synchronized(lock) {
                        check(!permanentlyClosed) { "JDK client transport is closed" }
                        check(active == null) { "A client transport already has an active connection" }
                        active = connection
                    }
                }
            } catch (failure: Throwable) {
                runCatching { delegate.close() }
                throw failure
            }

            try {
                val link = delegate.connect(sink)
                val accepted = synchronized(lock) {
                    if (permanentlyClosed || active !== connection) {
                        false
                    } else {
                        connection.link = link
                        true
                    }
                }
                if (!accepted) {
                    runCatching { link.close() }
                    runCatching { delegate.close() }
                    error("JDK client transport closed while connecting")
                }
                return object : ClientLink {
                    private val released = AtomicBoolean()

                    override fun send(payload: ByteArray): Boolean = synchronized(lock) {
                        if (permanentlyClosed || active !== connection) return@synchronized false
                        connection.link?.send(payload) ?: false
                    }

                    override fun close() {
                        if (released.compareAndSet(false, true)) closeConnection(connection)
                    }
                }
            } catch (failure: Throwable) {
                closeConnection(connection)
                throw failure
            }
        }

        override fun close() {
            val connection = synchronized(lock) {
                if (permanentlyClosed) return
                permanentlyClosed = true
                active.also { active = null }
            }
            clients.remove(this)
            connection?.let(::dispose)
        }

        private fun closeConnection(connection: Connection) {
            val owned = synchronized(lock) {
                if (active !== connection) return
                active = null
                connection
            }
            dispose(owned)
        }

        private fun dispose(connection: Connection) {
            runCatching { connection.link?.close() }
            runCatching { connection.delegate.close() }
        }

    }

    private class Connection(val delegate: ClientTransport) {
        @Volatile
        var link: ClientLink? = null
    }

    private companion object {
        const val CALLBACK_QUEUE_LIMIT = 1024
    }
}

/** Bounded, FIFO, serial execution over a shared worker pool. */
internal class OrderedPacketExecutor(
    private val backing: Executor,
    private val queueLimit: Int,
) : Executor {
    private val lock = Any()
    private val tasks = ArrayDeque<Runnable>()
    private var workerScheduled = false
    private var terminalSubmitted = false

    override fun execute(command: Runnable) = enqueue(command, terminal = false)

    /** Adds the one terminal notification after queued packet callbacks, reserving one queue slot. */
    fun executeTerminal(command: Runnable) = enqueue(command, terminal = true)

    private fun enqueue(command: Runnable, terminal: Boolean) {
        synchronized(lock) {
            if (terminalSubmitted) throw RejectedExecutionException("Connection callback queue is terminating")
            if ((!terminal && tasks.size >= queueLimit) || (terminal && tasks.size > queueLimit)) {
                throw RejectedExecutionException("Connection callback queue is full")
            }
            if (terminal) terminalSubmitted = true
            tasks.addLast(command)
            if (!workerScheduled) {
                workerScheduled = true
                try {
                    backing.execute(::drain)
                } catch (failure: RuntimeException) {
                    workerScheduled = false
                    tasks.removeLastOccurrence(command)
                    if (terminal) terminalSubmitted = false
                    throw failure
                }
            }
        }
    }

    private fun drain() {
        while (true) {
            val task = synchronized(lock) {
                tasks.pollFirst().also { if (it == null) workerScheduled = false }
            } ?: return
            try {
                task.run()
            } catch (failure: Throwable) {
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), failure)
            }
        }
    }
}
