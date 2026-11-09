/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server.net

import io.github.rwpp.api.net.transport.*
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.timeout.ReadTimeoutHandler
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One pool task drains a bounded batch of callbacks; no task is allocated for each frame. */
internal class CallbackQueue(private val executor: Executor, private val failure: () -> Unit) {
    private val items = ArrayDeque<Pair<Int, () -> Unit>>()
    private var bytes = 0L
    private var running = false
    private var terminal: (() -> Unit)? = null
    private var terminalSubmitted = false

    fun add(size: Int = 0, callback: () -> Unit): Boolean {
        var inline = false
        val accepted = synchronized(this) {
            if (terminalSubmitted) return false
            if (items.size >= 512 || bytes + size > 64L * 1024 * 1024) false
            else {
                items.add(size to callback); bytes += size
                if (!running) inline = !schedule()
                true
            }
        }
        if (!accepted || inline) runCatching(failure)
        if (inline) drain()
        return accepted
    }

    /** The one disconnect callback has a reserved slot, after every accepted callback. */
    fun addTerminal(callback: () -> Unit): Boolean {
        var inline = false
        synchronized(this) {
            if (terminalSubmitted) return false
            terminalSubmitted = true
            terminal = callback
            if (!running) inline = !schedule()
        }
        if (inline) {
            runCatching(failure); drain()
        }
        return true
    }

    private fun schedule(): Boolean {
        running = true
        return try {
            executor.execute(::drain); true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun drain() {
        while (true) {
            repeat(64) {
                val callback = synchronized(this) {
                    val item = items.poll()
                    if (item != null) {
                        bytes -= item.first; item.second
                    } else terminal.also { terminal = null }
                } ?: return@repeat
                // Application callback failures must still reach the reserved terminal slot.
                try {
                    callback()
                } catch (_: Throwable) {
                    runCatching(failure)
                }
            }
            synchronized(this) {
                if (items.isEmpty() && terminal == null) {
                    running = false; return
                }
                if (schedule()) return
                // During executor rejection/teardown keep draining this bounded mailbox on
                // the current thread; dropping its terminal callback leaks application peers.
            }
            runCatching(failure)
        }
    }
}

private class TcpWriter(private val channel: Channel, private val maxPayload: Int, private val maxMessages: Int) {
    private val writes = ArrayDeque<ByteArray>()
    private var bytes = 0L
    private var count = 0
    private var scheduled = false

    @Synchronized
    fun send(payload: ByteArray): Boolean {
        if (!channel.isActive || payload.size !in 1..maxPayload || count >= maxMessages || bytes + payload.size > 64L * 1024 * 1024) return false
        writes.add(payload.copyOf()); bytes += payload.size; count++
        kick(); return true
    }

    @Synchronized
    fun kick() {
        if (scheduled || writes.isEmpty() || !channel.isWritable || !channel.isActive) return
        scheduled = true
        try {
            channel.eventLoop().execute(::drain)
        } catch (_: RuntimeException) {
            scheduled = false; channel.close()
        }
    }

    private fun drain() = synchronized(this) {
        scheduled = false
        var sent = 0
        while (sent++ < 128 && channel.isWritable && channel.isActive) {
            val payload = writes.poll() ?: break
            val frame =
                channel.alloc().ioBuffer(payload.size + 4, payload.size + 4).writeInt(payload.size).writeBytes(payload)
            channel.write(frame)
                .addListener { result -> synchronized(this) { bytes -= payload.size; count--; if (!result.isSuccess) channel.close() } }
        }
        channel.flush(); kick()
    }
}

class NettyTcpServerTransport(
    private val runtime: ServerTransportBackend,
    private val config: TransportConfig,
    private val banner: TransportBanner? = null,
) : CountedServerTransport {
    private val peers = ConcurrentHashMap<ChannelId, Peer>()
    private val admitted = AtomicInteger()
    private val identifiers = AtomicLong(-1)
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private var server: Channel? = null

    @Volatile
    override var localAddress: TransportAddress? = null
        private set
    override val peerCount: Int get() = admitted.get()
    override fun start(listener: TransportListener) {
        check(!closed.get() && started.compareAndSet(false, true))
        try {
            server = ServerBootstrap().group(runtime.io).channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_REUSEADDR, true).childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, NativeTcpSocket.WATER_MARK)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        val peer = Peer(identifiers.getAndDecrement(), channel)
                        val callbacks = CallbackQueue(runtime.packetExecutor()) { channel.close() }
                        peers[channel.id()] = peer
                        channel.pipeline().addLast(ReadTimeoutHandler(config.idleTimeoutMillis, TimeUnit.MILLISECONDS))
                        channel.pipeline()
                            .addLast(LengthFieldBasedFrameDecoder(config.maxTcpPayloadBytes + 4, 0, 4, 0, 4))
                        channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                            private var first = true
                            private var joined = false
                            private var answering = false
                            override fun channelRead0(context: ChannelHandlerContext, frame: ByteBuf) {
                                if (answering) return
                                require(frame.readableBytes() in 1..config.maxTcpPayloadBytes)
                                val bytes = ByteArray(frame.readableBytes()); frame.readBytes(bytes)
                                if (first) {
                                    first = false
                                    val reply = banner?.answer(bytes)
                                    if (reply != null) {
                                        answering = true; context.writeAndFlush(Unpooled.wrappedBuffer(reply))
                                            .addListener(ChannelFutureListener.CLOSE); return
                                    }
                                    if (admitted.incrementAndGet() > config.maxPeers) {
                                        admitted.decrementAndGet(); channel.close(); return
                                    }
                                    joined = true
                                    callbacks.add { listener.connected(peer) }
                                }
                                callbacks.add(bytes.size) { listener.received(peer, bytes) }
                            }

                            override fun channelWritabilityChanged(context: ChannelHandlerContext) {
                                peer.writer.kick(); super.channelWritabilityChanged(context)
                            }

                            override fun channelInactive(context: ChannelHandlerContext) {
                                if (peers.remove(channel.id()) != null && joined) {
                                    admitted.decrementAndGet(); callbacks.addTerminal { listener.disconnected(peer) }
                                }
                            }

                            override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
                                channel.close()
                            }
                        })
                    }
                }).bind(config.bindHost, config.port).syncUninterruptibly().channel()
            val bound = server!!.localAddress() as InetSocketAddress
            localAddress = TransportAddress(bound.address.hostAddress, bound.port)
        } catch (problem: Throwable) {
            close(); throw problem
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        localAddress = null; server?.close(); peers.values.toList().forEach { it.close() }
    }

    private inner class Peer(override val id: Long, private val channel: Channel) : TransportPeer {
        val writer = TcpWriter(channel, config.maxPayloadBytes, config.writeQueueMessages)
        override val remoteHost: String? get() = (channel.remoteAddress() as? InetSocketAddress)?.address?.hostAddress
        override fun trySend(payload: ByteArray) = writer.send(payload)
        override fun close() {
            channel.close()
        }
    }
}

class NettyTcpClientTransport(
    private val runtime: ServerTransportBackend,
    private val config: TransportConfig,
    private val host: String,
    private val connectTimeoutMillis: Int,
    private val messages: TransportMessages,
) : ClientTransport {
    private val closed = AtomicBoolean()
    private var channel: Channel? = null
    override fun connect(sink: ClientSink): ClientLink {
        check(!closed.get() && channel == null)
        val failed = AtomicBoolean()
        fun fail(reason: String) {
            if (!closed.get() && failed.compareAndSet(false, true)) sink.failure(reason)
        }

        val callbacks = CallbackQueue(runtime.packetExecutor()) { channel?.close() }
        var writer: TcpWriter? = null
        val socket = Bootstrap().group(runtime.io).channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, NativeTcpSocket.WATER_MARK)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(channel: SocketChannel) {
                    writer = TcpWriter(channel, config.maxTcpPayloadBytes, config.writeQueueMessages)
                    channel.pipeline().addLast(LengthFieldBasedFrameDecoder(config.maxPayloadBytes + 4, 0, 4, 0, 4))
                    channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                        private var terminalReason: String? = null
                        override fun channelRead0(context: ChannelHandlerContext, frame: ByteBuf) {
                            require(frame.readableBytes() in 1..config.maxPayloadBytes)
                            val bytes = ByteArray(frame.readableBytes()); frame.readBytes(bytes)
                            callbacks.add(bytes.size) { sink.message(bytes) }
                        }

                        override fun channelWritabilityChanged(context: ChannelHandlerContext) {
                            writer?.kick(); super.channelWritabilityChanged(context)
                        }

                        override fun channelInactive(context: ChannelHandlerContext) {
                            callbacks.addTerminal { fail(terminalReason ?: messages.connectionClosed("TCP")) }
                        }

                        override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
                            terminalReason = messages.transportFailure("TCP", cause); context.close()
                        }
                    })
                }
            }).connect(host, config.port).syncUninterruptibly().channel()
        channel = socket
        return object : ClientLink {
            override fun send(payload: ByteArray) = !closed.get() && writer!!.send(payload)
            override fun close() {
                socket.close()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) channel?.close()
    }
}
