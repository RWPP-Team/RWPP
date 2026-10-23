package io.github.rwpp.server.net

import io.github.rwpp.api.net.impl.NativePacketSocket
import io.github.rwpp.api.net.transport.TransportRuntime
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.timeout.ReadTimeoutHandler
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native frame adapter; all channels share the server pools. */
class NativeTcpSocket(private val runtime: ServerTransportBackend = TransportRuntime.backend as ServerTransportBackend) : Socket(), NativePacketSocket {
    override val transportName: String get() = "TCP"
    @Volatile private var channel: Channel? = null
    private var timeout = 0
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private class Frame(val type: Int, val bytes: ByteArray)
    private val inbound = ArrayDeque<Frame>()
    private val outbound = ArrayDeque<Write>()
    // Each batch is confined to its single packet executor / channel event loop.
    private val receiveBatch = ArrayList<Frame>(64)
    private val writeBatch = ArrayList<Write>(16)
    private var inboundBytes = 0L
    private var inboundCount = 0
    private var outboundCapacity = 0L
    private var outboundCount = 0
    private var receiveScheduled = false
    private var readPaused = false
    private var resumeScheduled = false
    private var writeScheduled = false
    private var writeGeneration = 0L
    private var baselineFlushScheduled = false // Accessed only by the channel event loop.
    private var terminal: String? = null
    private var sealing = false
    private var sendAfterNanos = 0L
    private var receiver: ((Int, ByteArray) -> Unit)? = null
    private var disconnected: ((String) -> Unit)? = null
    private var pendingFailure: String? = null
    private val executor = runtime.packetExecutor()
    internal constructor(runtime: ServerTransportBackend, accepted: Channel) : this(runtime) { attach(accepted) }

    private fun attach(socket: Channel) {
        channel = socket
        // Frames scheduled for a future native delay have not yet entered Netty's outbound
        // buffer. Netty cannot release them when the channel closes, so the adapter must.
        socket.closeFuture().addListener { synchronized(this) { discardQueuedWrites() } }
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        check(channel == null && !closed.get())
        val result = Bootstrap().group(runtime.io).channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.AUTO_READ, false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout.takeIf { it > 0 } ?: 7000)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
            .option(ChannelOption.RCVBUF_ALLOCATOR, receiveAllocator(runtime.optimized))
            .handler(object : ChannelInitializer<SocketChannel>() { override fun initChannel(socket: SocketChannel) = Unit })
            .connect(endpoint).awaitUninterruptibly()
        if (!result.isSuccess) {
            result.channel().close()
            throw SocketException("TCP connect failed: ${result.cause()?.message}").apply { initCause(result.cause()) }
        }
        attach(result.channel())
    }
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 7000)

    override fun startReceiving(limit: () -> Int, receive: (Int, ByteArray) -> Unit, failure: (String) -> Unit) {
        startReceiving(limit, receive, failure, null)
    }

    override fun startReceiving(limit: () -> Int, receive: (Int, ByteArray) -> Unit, failure: (String) -> Unit,
                                fastReceive: ((Int, ByteArray) -> Boolean)?) {
        check(started.compareAndSet(false, true)) { "TCP receiver already started" }
        val socket = checkNotNull(channel)
        receiver = receive; disconnected = failure
        if (timeout > 0) socket.pipeline().addLast(ReadTimeoutHandler(timeout.toLong(), TimeUnit.MILLISECONDS))
        socket.pipeline().addLast(object : ByteToMessageDecoder() {
            init { if (runtime.optimized) setCumulator(COMPOSITE_CUMULATOR) }
            override fun channelRead(context: ChannelHandlerContext, message: Any) {
                super.channelRead(context, message)
                val flushReady = synchronized(this@NativeTcpSocket) {
                    if (runtime.optimized && inbound.isNotEmpty()) scheduleReceive()
                    runtime.optimized && socket.isWritable && outbound.peek()?.let {
                        it.buffer != null && it.due <= System.nanoTime()
                    } == true
                }
                // Keep a large read burst from delaying responses until the event-loop
                // task phase. The completed read buffer is already a useful write batch.
                if (flushReady) drainWrites()
            }
            override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
                if (input.readableBytes() < 8) return
                val size = input.getInt(input.readerIndex())
                if (size < 0 || size > limit()) throw CorruptedFrameException("Invalid native packet length: $size")
                synchronized(this@NativeTcpSocket) {
                    if (closed.get()) { input.skipBytes(input.readableBytes()); return }
                    if (runtime.optimized && (inboundCount >= 256 || inboundBytes + size + 8 > QUEUE_BYTES)) {
                        readPaused = true
                        socket.config().isAutoRead = false
                        return
                    }
                    if (input.readableBytes() < size + 8) return
                    if (inboundBytes + size + 8 > QUEUE_BYTES || inboundCount >= 512)
                        throw CorruptedFrameException("Native receive queue full")
                    input.skipBytes(4)
                    val type = input.readInt()
                    val bytes = ByteArray(size)
                    input.readBytes(bytes)
                    // Only explicitly bounded admission work may run here. A preceding
                    // queued/in-flight callback fences this path to preserve wire order.
                    if (runtime.optimized && size <= 64 * 1024 && inboundCount == 0 &&
                        fastReceive?.invoke(type, bytes) == true) return
                    inbound.add(Frame(type, bytes)); inboundBytes += size + 8L; inboundCount++
                    if (!runtime.optimized) executor.execute { deliverOne() }
                }
            }
            override fun channelReadComplete(context: ChannelHandlerContext) {
                val paused = synchronized(this@NativeTcpSocket) {
                    if (runtime.optimized && inboundCount >= 256) { readPaused = true; socket.config().isAutoRead = false }
                    readPaused
                }
                // ByteToMessageDecoder requests another read itself when it produced no pipeline
                // messages. Our decoder delivers through a mailbox, so that request would defeat
                // backpressure and could consume FIN while undecoded frames remain buffered.
                if (paused) context.fireChannelReadComplete() else super.channelReadComplete(context)
            }
            override fun channelWritabilityChanged(context: ChannelHandlerContext) {
                if (socket.isWritable) synchronized(this@NativeTcpSocket) { scheduleWrite() }
                super.channelWritabilityChanged(context)
            }
            override fun channelInactive(context: ChannelHandlerContext) {
                synchronized(this@NativeTcpSocket) { terminal = terminal ?: "TCP connection closed"; scheduleReceive() }
                super.channelInactive(context)
            }
            override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
                synchronized(this@NativeTcpSocket) { terminal = cause.message ?: "TCP failure" }
                context.close()
            }
        })
        socket.config().isAutoRead = true
    }

    private fun scheduleReceive() {
        if (receiveScheduled || closed.get()) return
        receiveScheduled = true
        try { executor.execute(::drainReceive) }
        catch (_: RuntimeException) { receiveScheduled = false; fail("Native packet executor full") }
    }
    private fun deliverOne(): Boolean {
        val frame = synchronized(this) { inbound.poll() } ?: return false
        try { if (!closed.get()) receiver?.invoke(frame.type, frame.bytes) }
        catch (problem: Throwable) { fail(problem.message ?: "Native packet callback failed") }
        finally { synchronized(this) {
            inboundBytes -= frame.bytes.size + 8L; inboundCount--
            scheduleFailureNotification()
        } }
        return true
    }
    private fun drainReceive() {
        val batch = receiveBatch
        var bytes = 0L
        synchronized(this) {
            repeat(64) {
                val frame = inbound.poll() ?: return@repeat
                batch.add(frame); bytes += frame.bytes.size + 8L
            }
        }
        try {
            for (frame in batch) {
                if (closed.get()) break
                try { receiver?.invoke(frame.type, frame.bytes) }
                catch (problem: Throwable) { fail(problem.message ?: "Native packet callback failed"); break }
            }
        } finally { synchronized(this) {
            // Keep in-flight callbacks charged against the same bounds as queued callbacks.
            inboundBytes -= bytes; inboundCount -= batch.size
            batch.clear()
            scheduleFailureNotification()
            receiveScheduled = false
            if (inbound.isNotEmpty()) scheduleReceive()
            else terminal?.let { fail(it) }
            if (inboundCount < 128 && readPaused && !resumeScheduled && !closed.get()) channel?.let { socket ->
                resumeScheduled = true
                socket.eventLoop().execute {
                    synchronized(this) {
                        resumeScheduled = false
                        if (closed.get()) return@execute
                        readPaused = false
                        socket.config().isAutoRead = true
                    }
                    // Resuming socket reads alone does not decode bytes already in the decoder's
                    // cumulation. Trigger a decode even if no new bytes arrive from the peer.
                    socket.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER)
                }
            }
        } }
    }

    override fun send(type: Int, bytes: ByteArray, delayAfterMillis: Int): Boolean =
        if (runtime.optimized) enqueue(type, bytes, delayAfterMillis)
        else synchronized(this) { enqueue(type, bytes, delayAfterMillis) }

    private fun enqueue(type: Int, bytes: ByteArray, delayAfterMillis: Int): Boolean {
        val socket = channel ?: return false
        val write: Write
        synchronized(this) {
            if (closed.get() || sealing || !socket.isActive) return false
            if (bytes.size > NativePacketSocket.MAX_PACKET_BYTES || outboundCount >= 512) {
                fail("Native send queue full"); return false
            }
            val now = System.nanoTime()
            val due = maxOf(now, sendAfterNanos)
            // Snapshot once into the final outbound buffer: callers may reuse packet arrays
            // after send returns. The queue owns this reference until socket.write takes it.
            val tail = outbound.peekLast()
            if (runtime.optimized && tail != null && sendAfterNanos <= now && tail.due <= now &&
                tail.buffer?.writableBytes()?.let { it >= bytes.size + 8 } == true) {
                tail.buffer!!.writeInt(bytes.size).writeInt(type).writeBytes(bytes)
                tail.size += bytes.size + 8
                tail.frames++
                outboundCount++
                sendAfterNanos = due + TimeUnit.MILLISECONDS.toNanos(delayAfterMillis.coerceAtLeast(0).toLong())
                scheduleWrite()
                return true
            }
            val capacity = if (runtime.optimized && due == now) maxOf(256 * 1024, bytes.size + 8) else bytes.size + 8
            if (outboundCapacity + capacity > QUEUE_BYTES) { fail("Native send queue full"); return false }
            // Reserve a FIFO slot and its full capacity before allocating. A slow
            // allocator must not hold the receive mailbox lock or reorder senders.
            write = Write(capacity, bytes.size + 8, due)
            outboundCapacity += capacity; outboundCount++
            outbound.add(write)
            sendAfterNanos = due + TimeUnit.MILLISECONDS.toNanos(delayAfterMillis.coerceAtLeast(0).toLong())
        }
        val buffer: ByteBuf
        try {
            buffer = allocateWriteBuffer(socket, write.capacity)
            try { buffer.writeInt(bytes.size).writeInt(type).writeBytes(bytes) }
            catch (problem: Throwable) { buffer.release(); throw problem }
        } catch (problem: Throwable) {
            synchronized(this) { fail("TCP buffer allocation failed") }
            throw problem
        }
        synchronized(this) {
            if (write.cancelled) { buffer.release(); return false }
            if (closed.get() || !socket.isActive) {
                buffer.release(); outbound.remove(write); write.cancelled = true
                completeWrites(write.capacity.toLong(), write.frames)
                finishCloseIfReady()
                return false
            }
            write.buffer = buffer
            if (runtime.optimized) scheduleWrite()
            else {
                try { socket.eventLoop().schedule({
                    synchronized(this) {
                        if (write.cancelled) return@schedule
                        if (outbound.peek() === write) outbound.remove() else outbound.remove(write)
                        writeFrame(write)
                    }
                    // Preserve the previous adapter's coalesced flush when comparing task batching.
                    if (!baselineFlushScheduled) {
                        baselineFlushScheduled = true
                        socket.eventLoop().execute { baselineFlushScheduled = false; socket.flush() }
                    }
                }, (write.due - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
                catch (_: RuntimeException) { fail("TCP write rejected"); return false }
            }
            return true
        }
    }
    private fun allocateWriteBuffer(socket: Channel, capacity: Int): ByteBuf {
        if (!runtime.optimized || capacity <= 1024 * 1024)
            return socket.alloc().ioBuffer(capacity, capacity)
        // AdaptiveByteBufAllocator does not pool multi-MiB allocations. Keep a
        // large native frame in pooled pieces; gathering writes preserve its wire
        // layout and send still snapshots each byte exactly once.
        val partSize = 1024 * 1024
        val result = socket.alloc().compositeDirectBuffer((capacity + partSize - 1) / partSize)
        try {
            var remaining = capacity
            while (remaining > 0) {
                val size = minOf(partSize, remaining)
                result.addComponent(false, socket.alloc().ioBuffer(size, size).writerIndex(size))
                remaining -= size
            }
            return result
        } catch (problem: Throwable) {
            result.release()
            throw problem
        }
    }
    private fun scheduleWrite() {
        val socket = channel ?: return
        if (!runtime.optimized) return
        if (writeScheduled || closed.get() || !socket.isActive || !socket.isWritable) return
        if (outbound.isEmpty() && !sealing) return
        if (outbound.peek()?.buffer == null && outbound.isNotEmpty()) return
        writeScheduled = true
        val generation = ++writeGeneration
        val task = Runnable {
            if (synchronized(this) { writeScheduled && generation == writeGeneration }) drainWrites()
        }
        val delay = ((outbound.peek()?.due ?: 0) - System.nanoTime()).coerceAtLeast(0)
        try {
            if (delay == 0L) socket.eventLoop().execute(task)
            else socket.eventLoop().schedule(task, delay, TimeUnit.NANOSECONDS)
        }
        catch (_: RuntimeException) { writeScheduled = false; fail("TCP write rejected") }
    }
    private fun drainWrites() {
        val socket = channel ?: return
        val batch = writeBatch
        var bytes = 0L
        synchronized(this) {
            writeScheduled = false
            val writableBytes = socket.bytesBeforeUnwritable().coerceAtLeast(1)
            while (batch.size < 128 && socket.isActive && socket.isWritable) {
                val next = outbound.peek() ?: break
                if (next.buffer == null) break
                if (next.due > System.nanoTime()) break
                outbound.remove(); batch.add(next); bytes += next.size
                if (bytes >= writableBytes) break
            }
        }
        // The event loop owns this batch now. Never hold the mailbox lock across
        // write/flush: a kernel write must not block incoming frames or callbacks.
        if (batch.isNotEmpty()) {
            val count = batch.size
            val frames = batch.sumOf { it.frames }
            val capacity = batch.sumOf { it.capacity.toLong() }
            var handedOff = 0
            var completed = false
            fun completeBatch() {
                if (!completed) { completed = true; completeWrites(capacity, frames) }
            }
            try {
                for (index in 0 until count - 1) {
                    handedOff++
                    socket.write(batch[index].buffer!!, socket.voidPromise())
                }
                // FIFO completion of the final frame covers the preceding void-promise
                // writes. Channel exceptions also close/fail this final promise.
                handedOff++
                socket.write(batch.last().buffer!!).addListener { result ->
                    synchronized(this) {
                        completeBatch()
                        if (!result.isSuccess) fail("TCP write failed") else finishCloseIfReady()
                    }
                }
                socket.flush()
            } catch (_: RuntimeException) {
                // A write transfers ownership to Netty even when it reports failure.
                // Only frames whose write was never invoked still belong to this queue.
                for (index in handedOff until count) batch[index].buffer!!.release()
                synchronized(this) { completeBatch(); fail("TCP write rejected") }
            }
        }
        batch.clear()
        synchronized(this) {
            if (outbound.isNotEmpty()) scheduleWrite() else finishCloseIfReady()
        }
    }
    private fun writeFrame(write: Write) {
        val socket = channel ?: return
        socket.write(write.buffer!!).addListener { result ->
            synchronized(this) { completeWrites(write.capacity.toLong(), write.frames); if (!result.isSuccess) fail("TCP write failed") else finishCloseIfReady() }
        }
    }
    private fun completeWrites(capacity: Long, count: Int) { outboundCapacity -= capacity; outboundCount -= count }
    private fun discardQueuedWrites() {
        while (true) {
            val write = outbound.poll() ?: break
            write.cancelled = true
            write.buffer?.release()
            completeWrites(write.capacity.toLong(), write.frames)
        }
    }
    private fun discardQueuedReceives() {
        while (true) {
            val frame = inbound.poll() ?: break
            inboundBytes -= frame.bytes.size + 8L; inboundCount--
        }
    }
    private fun finishCloseIfReady() {
        if (sealing && outboundCount == 0) channel?.writeAndFlush(Unpooled.EMPTY_BUFFER)?.addListener(ChannelFutureListener.CLOSE)
    }
    @Synchronized override fun closeAfterWrites() {
        if (sealing || closed.get()) return
        sealing = true
        scheduleWrite(); finishCloseIfReady()
        channel?.let { socket -> socket.eventLoop().schedule({ socket.close() }, 5, TimeUnit.SECONDS) }
    }
    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            pendingFailure = reason
            discardQueuedReceives(); discardQueuedWrites()
            scheduleFailureNotification()
        }
        channel?.close()
    }
    /** Called under the mailbox lock; application teardown must never inherit that lock. */
    private fun scheduleFailureNotification() {
        if (inboundCount != 0) return // An in-flight receiver must finish first.
        val reason = pendingFailure ?: return
        pendingFailure = null // Reserve the single notification before submitting.
        val notify = Runnable {
            // Submission can occur inside an outer synchronized send/decode call.
            // Cross this barrier, then release it before taking any game connection lock.
            synchronized(this) { }
            runCatching { disconnected?.invoke(reason) }
        }
        try { executor.execute(notify) }
        catch (_: RuntimeException) {
            // Shutdown or a full packet executor must not lose peer cleanup. Never run
            // inline here: the caller may still hold the socket monitor.
            java.util.concurrent.ForkJoinPool.commonPool().execute(notify)
        }
    }
    override fun close() { closed.set(true); synchronized(this) { discardQueuedReceives(); discardQueuedWrites() }; channel?.close() }
    override fun isClosed() = closed.get() || channel?.isOpen == false
    override fun isConnected() = channel != null
    override fun isBound() = channel?.localAddress() != null
    override fun getInetAddress() = (channel?.remoteAddress() as? InetSocketAddress)?.address
    override fun getPort() = (channel?.remoteAddress() as? InetSocketAddress)?.port ?: 0
    override fun getLocalAddress() = (channel?.localAddress() as? InetSocketAddress)?.address
    override fun getLocalPort() = (channel?.localAddress() as? InetSocketAddress)?.port ?: -1
    override fun getRemoteSocketAddress() = channel?.remoteAddress()
    override fun getLocalSocketAddress() = channel?.localAddress()
    override fun setTcpNoDelay(on: Boolean) { channel?.config()?.setOption(ChannelOption.TCP_NODELAY, on) }
    override fun getTcpNoDelay() = true
    override fun setSoTimeout(timeout: Int) { require(timeout >= 0); this.timeout = timeout }
    override fun getSoTimeout() = timeout
    override fun getInputStream(): InputStream = throw SocketException("Native TCP uses packet callbacks")
    override fun getOutputStream(): OutputStream = throw SocketException("Native TCP uses packet callbacks")
    private class Write(val capacity: Int, var size: Int, val due: Long) {
        var buffer: ByteBuf? = null
        var cancelled = false
        var frames = 1
    }
    companion object {
        const val MAX_PACKET_BYTES = NativePacketSocket.MAX_PACKET_BYTES
        private const val QUEUE_BYTES = 64L * 1024 * 1024
        internal val WATER_MARK = WriteBufferWaterMark(64 * 1024, 256 * 1024)
        internal fun receiveAllocator(optimized: Boolean) =
            if (optimized) AdaptiveRecvByteBufAllocator(64, 8192, 1024 * 1024) else AdaptiveRecvByteBufAllocator()
    }
}

class NativeTcpServerSocket(private val runtime: ServerTransportBackend, port: Int) : ServerSocket() {
    constructor(port: Int) : this(TransportRuntime.backend as ServerTransportBackend, port)
    private val accepted = ArrayBlockingQueue<NativeTcpSocket>(128)
    private val closed = AtomicBoolean()
    private val channel = ServerBootstrap().group(runtime.io).channel(NioServerSocketChannel::class.java)
        .option(ChannelOption.SO_REUSEADDR, true).childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.SO_KEEPALIVE, true).childOption(ChannelOption.AUTO_READ, false)
        .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, NativeTcpSocket.WATER_MARK)
        .childOption(ChannelOption.RCVBUF_ALLOCATOR, NativeTcpSocket.receiveAllocator(runtime.optimized))
        .childHandler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(socket: SocketChannel) {
                if (closed.get() || !accepted.offer(NativeTcpSocket(runtime, socket))) socket.close()
            }
        }).bind(port).syncUninterruptibly().channel()
    override fun accept(): Socket {
        while (!closed.get()) {
            val socket = accepted.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (!socket.isClosed) return socket
        }
        throw SocketException("Server socket closed")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close(); while (true) (accepted.poll() ?: break).close()
    }
    override fun isClosed() = closed.get()
    override fun isBound() = channel.isActive
    override fun getLocalPort() = (channel.localAddress() as InetSocketAddress).port
    override fun getInetAddress(): InetAddress = (channel.localAddress() as InetSocketAddress).address
}
