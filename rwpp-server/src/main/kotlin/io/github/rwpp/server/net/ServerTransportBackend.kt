package io.github.rwpp.server.net

import io.github.rwpp.api.net.impl.NativeKcpSocket
import io.github.rwpp.api.net.transport.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.RejectedExecutionHandlers
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Shared server resources. A connection never creates its own I/O or callback thread. */
class ServerTransportBackend : TransportBackend {
    override val supportsNativeServer: Boolean get() = true
    private val closed = AtomicBoolean()
    val optimized: Boolean = System.getProperty("rwpp.server.network.optimized", "true").toBooleanStrict()
    internal val io = NioEventLoopGroup(
        System.getProperty("rwpp.server.network.ioThreads", "4").toInt().coerceIn(1, 32),
        DefaultThreadFactory("rwpp-server-io", true),
    )
    internal val packets = DefaultEventExecutorGroup(
        System.getProperty("rwpp.server.network.packetThreads", "4").toInt().coerceIn(1, 64),
        DefaultThreadFactory("rwpp-server-packets", true), 4096, RejectedExecutionHandlers.reject(),
    )
    override val scheduler = ScheduledThreadPoolExecutor(1, DefaultThreadFactory("rwpp-server-timer", true)).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    override fun packetExecutor(): Executor = packets.next()
    override fun nativeClientSocket(tcpOnly: Boolean): Socket = if (tcpOnly) NativeTcpSocket(this) else NativeKcpSocket()
    override fun nativeTcpListener(port: Int) = NativeTcpServerSocket(this, port)
    override fun nativeKcpListener(port: Int) = NativeKcpServerSocket(this, port)
    override fun kcpClient(config: TransportConfig, host: String, messages: TransportMessages): ClientTransport =
        NettyKcpClientTransport(this, config, host, messages)
    override fun tcpClient(config: TransportConfig, host: String, timeout: Int, messages: TransportMessages): ClientTransport =
        NettyTcpClientTransport(this, config, host, timeout, messages)
    override fun kcpServer(config: TransportConfig): ServerTransport = NettyKcpServerTransport(this, config)
    override fun tcpServer(config: TransportConfig, banner: TransportBanner?): ServerTransport = NettyTcpServerTransport(this, config, banner)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scheduler.shutdownNow()
        val ioStopped = io.shutdownGracefully(0, 3, TimeUnit.SECONDS)
        val packetStopped = packets.shutdownGracefully(0, 3, TimeUnit.SECONDS)
        if (io.none { it.inEventLoop() } && packets.none { it.inEventLoop() }) {
            ioStopped.syncUninterruptibly()
            packetStopped.syncUninterruptibly()
        }
    }
}
