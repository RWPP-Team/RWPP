/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server.net

import io.github.rwpp.api.net.kcp.Kcp
import io.github.rwpp.api.net.transport.*
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.concurrent.EventExecutor
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** UDP writes cross into the channel event loop once per batch, rather than once per segment. */
internal class DatagramWriter(private val channel: Channel, private val optimized: Boolean) {
    private val queue = ArrayDeque<Pair<InetSocketAddress, ByteArray>>()
    private var bytes = 0L
    private var inflight = 0
    private var scheduled = false
    val rejectedDatagrams = AtomicLong()

    @Synchronized
    fun send(remote: InetSocketAddress, data: ByteArray): Boolean {
        if (!channel.isActive || queue.size >= 32768 || bytes + data.size > 64L * 1024 * 1024) {
            rejectedDatagrams.incrementAndGet(); return false
        }
        if (!optimized) {
            // Keep a same-binary unbatched comparison path.
            if (!channel.isWritable || inflight >= 32768) {
                rejectedDatagrams.incrementAndGet(); return false
            }
            bytes += data.size; inflight++
            channel.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(data), remote)).addListener {
                synchronized(this) { bytes -= data.size; inflight-- }
            }
            return true
        }
        queue.add(remote to data); bytes += data.size
        kick(); return true
    }

    @Synchronized
    fun kick() {
        if (scheduled || queue.isEmpty() || !channel.isActive || !channel.isWritable) return
        scheduled = true
        try {
            channel.eventLoop().execute(::drain)
        } catch (_: RuntimeException) {
            scheduled = false; channel.close()
        }
    }

    private fun drain() = synchronized(this) {
        scheduled = false
        var count = 0
        while (count++ < 256 && channel.isWritable && channel.isActive) {
            val (remote, payload) = queue.poll() ?: break
            bytes -= payload.size
            channel.write(DatagramPacket(Unpooled.wrappedBuffer(payload), remote))
        }
        channel.flush(); kick()
    }
}

/** The API KCP core is confined to ordered shards; each shard shares one maintenance timer. */
class NettyKcpServerTransport(
    private val runtime: ServerTransportBackend,
    private val config: TransportConfig,
    private val clientRemote: InetSocketAddress? = null,
) : CountedServerTransport {
    private val peers = ConcurrentHashMap<InetSocketAddress, Session>()
    private val admissions = AdmissionLimit(minOf(config.maxPeers, 128))
    private val ids = AtomicLong()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val attempts = HashMap<String, Pair<Long, Int>>()
    private var channel: Channel? = null
    private var writer: DatagramWriter? = null
    private lateinit var listener: TransportListener
    private val shards = runtime.packets.take(minOf(config.messageThreads, runtime.packets.count())).map { Shard(it) }

    @Volatile
    override var localAddress: TransportAddress? = null
        private set
    override val peerCount: Int get() = peers.size

    override fun start(listener: TransportListener) {
        check(!closed.get() && started.compareAndSet(false, true))
        this.listener = listener
        try {
            channel = Bootstrap().group(runtime.io).channel(NioDatagramChannel::class.java)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, maxOf(1024 * 1024, config.mtu * config.readQueueDatagrams))
                .option(ChannelOption.SO_SNDBUF, maxOf(1024 * 1024, config.mtu * config.writeQueueMessages))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(256 * 1024, 1024 * 1024))
                .handler(object : SimpleChannelInboundHandler<DatagramPacket>() {
                    override fun channelRead0(context: ChannelHandlerContext, packet: DatagramPacket) {
                        if (closed.get() || (clientRemote != null && packet.sender() != clientRemote)) return
                        val buffer = packet.content()
                        if (buffer.readableBytes() !in 24..config.mtu) return
                        val bytes = ByteArray(buffer.readableBytes()); buffer.getBytes(buffer.readerIndex(), bytes)
                        if (!valid(bytes)) return
                        shard(packet.sender()).offer(packet.sender(), bytes)
                    }

                    override fun channelWritabilityChanged(context: ChannelHandlerContext) {
                        writer?.kick(); super.channelWritabilityChanged(context)
                    }

                    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
                        context.close()
                    }

                    override fun channelInactive(context: ChannelHandlerContext) {
                        close()
                    }
                }).bind(config.bindHost, config.port).syncUninterruptibly().channel()
            writer = DatagramWriter(channel!!, runtime.optimized)
            val address = channel!!.localAddress() as InetSocketAddress
            localAddress = TransportAddress(address.address.hostAddress, address.port)
            shards.forEach { it.start() }
            if (clientRemote != null) {
                val selected = shard(clientRemote)
                selected.executor.submit { selected.create(clientRemote) }.syncUninterruptibly()
            }
        } catch (problem: Throwable) {
            close(); throw problem
        }
    }

    private fun shard(address: InetSocketAddress): Shard = shards[(address.hashCode() and Int.MAX_VALUE) % shards.size]
    private fun valid(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (data.size - offset < 24 || intLE(data, offset) != config.conv) return false
            val command = data[offset + 4].toInt() and 255
            val fragments = data[offset + 5].toInt() and 255
            val length = intLE(data, offset + 20)
            if (command !in 81..84 || length < 0 || length > data.size - offset - 24) return false
            if (command == 81) {
                if (fragments >= config.maximumFragments || length > config.mtu - 24) return false
            } else if (length != 0 || fragments != 0) return false
            offset += 24 + length
        }
        return offset == data.size
    }

    @Synchronized
    private fun permitAttempt(address: InetSocketAddress, now: Long): Boolean {
        val host = address.address?.hostAddress ?: address.hostString
        if (attempts.size >= 1024) attempts.entries.removeIf { now - it.value.first >= 1000 }
        val previous = attempts[host]
        if (previous == null && attempts.size >= 1024) return false
        val current =
            if (previous == null || now - previous.first >= 1000) now to 1 else previous.first to previous.second + 1
        attempts[host] = current
        return current.second <= 256
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        localAddress = null
        channel?.close()
        shards.forEach { it.timer?.cancel(false) }
        val stops = shards.map { shard ->
            shard.executor.submit {
                shard.sessions.values.toList().forEach { shard.remove(it) }
            }
        }
        if (runtime.packets.none { it.inEventLoop() } && runtime.io.none { it.inEventLoop() }) stops.forEach { it.syncUninterruptibly() }
    }

    private inner class Shard(val executor: EventExecutor) {
        val sessions = LinkedHashMap<InetSocketAddress, Session>()
        private val incoming = ArrayDeque<Pair<InetSocketAddress, ByteArray>>()
        private var scheduled = false
        var timer: io.netty.util.concurrent.ScheduledFuture<*>? = null
        fun start() {
            timer = executor.scheduleAtFixedRate(
                ::maintain,
                config.intervalMillis.toLong(),
                config.intervalMillis.toLong(),
                TimeUnit.MILLISECONDS
            )
        }

        @Synchronized
        fun offer(address: InetSocketAddress, bytes: ByteArray) {
            if (incoming.size >= minOf(8192, config.readQueueDatagrams * config.maxPeers)) return
            incoming.add(address to bytes); kick()
        }

        @Synchronized
        fun kick() {
            if (scheduled || closed.get()) return
            scheduled = true
            try {
                executor.execute(::drain)
            } catch (_: RuntimeException) {
                scheduled = false
            }
        }

        fun create(address: InetSocketAddress): Session? {
            if (closed.get() || !admissions.tryAcquire(address)) return null
            val session = Session(ids.incrementAndGet(), address, this)
            sessions[address] = session; peers[address] = session
            try {
                listener.connected(session)
            } catch (_: Exception) {
                session.closeRequested = true
            }
            return session
        }

        private fun drain() {
            val time = now()
            repeat(256) {
                val item = synchronized(this) { incoming.poll() } ?: return@repeat
                val (address, bytes) = item
                var session = sessions[address]
                if (session == null) {
                    if (clientRemote != null || bytes[4].toInt() != 81 || intLE(bytes, 12) != 0 || !permitAttempt(
                            address,
                            System.nanoTime() / 1_000_000L
                        )
                    ) return@repeat
                    session = create(address) ?: return@repeat
                }
                if (session.closeRequested || !session.protocol.input(bytes, time)) {
                    session.closeRequested = true; return@repeat
                }
                session.lastActive = time
                session.needsFlush = true
            }
            maintain()
            synchronized(this) {
                scheduled = false
                if (incoming.isNotEmpty() || sessions.values.any { it.hasWrites() }) kick()
            }
        }

        private fun maintain() {
            if (closed.get()) return
            val time = now()
            val iterator = sessions.values.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                if (session.closeRequested || session.protocol.isDead || time - session.lastActive >= config.idleTimeoutMillis) {
                    iterator.remove(); finishRemove(session); continue
                }
                try {
                    val wrote = session.sendQueued()
                    session.protocol.update(time)
                    if (wrote || session.needsFlush) {
                        session.protocol.flushPending(time)
                        session.needsFlush = false
                    }
                    repeat(64) {
                        val payload = session.protocol.receive() ?: return@repeat
                        if (payload.size !in 1..config.maxPayloadBytes) {
                            session.closeRequested = true; return@repeat
                        }
                        listener.received(session, payload)
                    }
                } catch (_: Exception) {
                    session.closeRequested = true
                }
            }
        }

        fun remove(session: Session) {
            if (sessions.remove(session.address) != null) finishRemove(session)
        }

        private fun finishRemove(session: Session) {
            session.closeRequested = true
            peers.remove(session.address, session); admissions.release(session.address)
            session.clearWrites()
            runCatching { listener.disconnected(session) }
        }
    }

    private inner class Session(override val id: Long, val address: InetSocketAddress, val owner: Shard) :
        TransportPeer {
        @Volatile
        var closeRequested = false
        var lastActive = now()
        var needsFlush = false
        private val outgoing = ArrayDeque<ByteArray>()
        private var queuedBytes = 0L

        // Window advertisements reserve whole MTU slots. Keep the receive budget
        // independent of sendWindow so a small final fragment cannot be stranded.
        val protocol = Kcp(
            config.conv, config.mtu, config.sendWindow, config.receiveWindow, config.intervalMillis,
            maxOf(
                config.mtu * config.sendWindow * 3, config.mtu * config.receiveWindow,
                config.maxPayloadBytes + config.maximumFragments * 24
            )
        ) { data ->
            // Local UDP queue pressure is packet loss, which KCP already handles through
            // bounded retransmission. Do not disconnect unrelated peers sharing this socket.
            // A permanently closed channel is terminal; a full live queue only drops output.
            if (writer?.send(address, data) != true && channel?.isActive != true) closeRequested = true
        }
        override val remoteHost: String? get() = address.address?.hostAddress
        override fun trySend(payload: ByteArray): Boolean {
            synchronized(this) {
                if (closed.get() || closeRequested || payload.size !in 1..config.maxPayloadBytes || outgoing.size >= config.writeQueueMessages || queuedBytes + payload.size > 64L * 1024 * 1024) return false
                outgoing.add(payload.copyOf()); queuedBytes += payload.size
            }
            owner.kick(); return true
        }

        @Synchronized
        fun hasWrites() = outgoing.isNotEmpty() && protocol.pendingSegments < config.sendWindow * 2

        @Synchronized
        fun sendQueued(): Boolean {
            var count = 0
            var wrote = false
            while (count++ < 16 && protocol.pendingSegments < config.sendWindow * 2) {
                val payload = outgoing.peek() ?: break
                if (!protocol.send(payload)) break
                outgoing.remove(); queuedBytes -= payload.size
                wrote = true
            }
            return wrote
        }

        @Synchronized
        fun clearWrites() {
            outgoing.clear(); queuedBytes = 0
        }

        override fun close() {
            closeRequested = true; owner.kick()
        }
    }

    internal fun clientPeer(): TransportPeer = checkNotNull(clientRemote?.let { peers[it] })

    private companion object {
        fun now() = (System.nanoTime() / 1_000_000L).toInt()
        fun intLE(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or
                ((bytes[offset + 1].toInt() and 255) shl 8) or ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
    }
}

class NettyKcpClientTransport(
    private val runtime: ServerTransportBackend,
    private val config: TransportConfig,
    private val host: String,
    private val messages: TransportMessages,
) : ClientTransport {
    private val closed = AtomicBoolean()
    private var endpoint: NettyKcpServerTransport? = null
    override fun connect(sink: ClientSink): ClientLink {
        check(!closed.get() && endpoint == null)
        val failed = AtomicBoolean()
        val server = NettyKcpServerTransport(
            runtime,
            config.copy(bindHost = "0.0.0.0", port = 0, maxPeers = 1),
            InetSocketAddress(host, config.port)
        )
        endpoint = server
        server.start(object : TransportListener {
            override fun connected(peer: TransportPeer) = Unit
            override fun received(peer: TransportPeer, payload: ByteArray) {
                if (!closed.get()) sink.message(payload)
            }

            override fun disconnected(peer: TransportPeer) {
                if (!closed.get() && failed.compareAndSet(false, true)) sink.failure(messages.connectionClosed("KCP"))
            }
        })
        val peer = server.clientPeer()
        return object : ClientLink {
            override fun send(payload: ByteArray) = peer.trySend(payload)
            override fun close() {
                peer.close()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) endpoint?.close()
    }
}
