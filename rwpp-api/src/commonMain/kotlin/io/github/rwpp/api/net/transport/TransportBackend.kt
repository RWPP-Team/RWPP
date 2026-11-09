/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.net.transport

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/** Platform I/O belongs to client/server; the API owns protocols and contracts. */
interface TransportBackend : AutoCloseable {
    val supportsNativeServer: Boolean get() = false
    val scheduler: ScheduledExecutorService

    /** A serial executor dedicated to one connection, backed by the platform's shared pool. */
    fun packetExecutor(): Executor
    fun nativeClientSocket(tcpOnly: Boolean): Socket
    fun nativeTcpListener(port: Int): ServerSocket = error("This backend does not host native TCP")
    fun nativeKcpListener(port: Int): ServerSocket = error("This backend does not host native KCP")
    fun kcpClient(config: TransportConfig, host: String, messages: TransportMessages): ClientTransport
    fun tcpClient(config: TransportConfig, host: String, timeout: Int, messages: TransportMessages): ClientTransport
    fun kcpServer(config: TransportConfig): ServerTransport = error("This backend does not host KCP")
    fun tcpServer(config: TransportConfig, banner: TransportBanner?): ServerTransport =
        error("This backend does not host TCP")
}

interface CountedServerTransport : ServerTransport {
    val peerCount: Int
}

object TransportRuntime : AutoCloseable {
    @Volatile
    private var current: TransportBackend? = null
    val isInstalled: Boolean get() = current != null
    val backend: TransportBackend get() = checkNotNull(current) { "Install a client or server transport backend before networking" }

    @Synchronized
    fun install(backend: TransportBackend) {
        check(current == null || current === backend) { "A transport backend is already installed" }
        current = backend
    }

    @Synchronized
    fun installIfAbsent(factory: () -> TransportBackend): TransportBackend =
        current ?: factory().also { current = it }

    override fun close() {
        val active = synchronized(this) { current } ?: return
        try {
            active.close()
        } finally {
            synchronized(this) { if (current === active) current = null }
        }
    }
}
