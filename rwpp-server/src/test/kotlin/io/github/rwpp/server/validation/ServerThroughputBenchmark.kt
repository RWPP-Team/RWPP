package io.github.rwpp.server.validation

import io.github.rwpp.api.net.impl.NativePacketSocket
import io.github.rwpp.server.net.NativeTcpServerSocket
import io.github.rwpp.server.net.ServerTransportBackend
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.all
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.drop
import kotlin.collections.filter
import kotlin.collections.filterIsInstance
import kotlin.collections.firstOrNull
import kotlin.collections.flatMap
import kotlin.collections.forEach
import kotlin.collections.getOrElse
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.linkedMapOf
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.none
import kotlin.collections.setOf
import kotlin.collections.sorted
import kotlin.collections.sumOf
import kotlin.math.max

/** Opt-in closed-loop component benchmark. Vanilla models native worker I/O without the game engine. */
object ServerThroughputBenchmark {
    private const val TYPE = 0x52575050
    private const val PREFIX = 24
    private val cpuBean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "server") {
            server(args[1], args[2].toInt(), args[3].toInt())
            return
        }
        require(args.size >= 5) { "optimized|previous|vanilla connections payloadBytes window seconds [serverCpus]" }
        val result = client(
            args[0], args[1].toInt(), args[2].toInt(), args[3].toInt(),
            args[4].toInt(), args.getOrElse(5) { "0-3" }, args.getOrElse(6) { "3" }.toInt()
        )
        println(json(result))
        if (result["errors"] != 0 || result["errors_including_drain"] != 0 || result["incomplete"] != 0L ||
            result["echo_count_matches_client_completions"] != true || result["drained"] != true
        ) {
            kotlin.system.exitProcess(2)
        }
    }

    private fun server(mode: String, peers: Int, bytes: Int) {
        val runtime = if (mode == "vanilla") null else ServerTransportBackend()
        val listener = runtime?.let { NativeTcpServerSocket(it, 0) } ?: ServerSocket(0)
        val sockets = ConcurrentLinkedQueue<Socket>()
        val stopping = AtomicBoolean()
        val errors = LongAdder()
        val echoes = LongAdder()
        val inlineReceive = System.getProperty("rwpp.benchmark.inlineReceive") == "true"
        val peakThreads = AtomicInteger(ManagementFactory.getThreadMXBean().threadCount)
        val sampler = Thread({
            while (!stopping.get()) {
                peakThreads.accumulateAndGet(ManagementFactory.getThreadMXBean().threadCount, ::max)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            }
        }, "benchmark-server-sampler").apply { isDaemon = true; start() }
        Thread({
            repeat(peers) {
                try {
                    val raw = listener.accept()
                    val accepted = if (mode == "vanilla") VanillaPacketSocket(raw) else raw
                    sockets.add(accepted)
                    val socket = accepted as NativePacketSocket
                    val echo: (Int, ByteArray) -> Unit = { type, payload ->
                        if (type != TYPE || payload.size != bytes) errors.increment()
                        else {
                            if (ByteBuffer.wrap(payload).getInt(16) == 2) echoes.increment()
                            if (!socket.send(type, payload)) errors.increment()
                        }
                    }
                    val failure: (String) -> Unit = { if (!stopping.get()) errors.increment() }
                    val limit = { max(bytes, 2_000_000) }
                    if (inlineReceive && mode != "vanilla") {
                        socket.startReceiving(limit, echo, failure) { type, payload ->
                            echo(type, payload)
                            true
                        }
                    } else {
                        // Keep the vanilla reference on its original three-argument callback.
                        socket.startReceiving(limit, echo, failure)
                    }
                } catch (_: Exception) {
                    if (!stopping.get()) errors.increment()
                }
            }
        }, "benchmark-accept").apply { isDaemon = true; start() }
        println("READY ${listener.localPort}")
        System.out.flush()
        var begin: Snapshot? = null
        var end: Snapshot? = null
        try {
            while (true) {
                when (readlnOrNull()) {
                    "BEGIN" -> {
                        peakThreads.set(ManagementFactory.getThreadMXBean().threadCount)
                        begin = snapshot(echoes.sum(), errors.sum())
                        println("BEGIN ${begin.wire()}")
                        System.out.flush()
                    }

                    "END" -> {
                        val before = checkNotNull(begin)
                        val after = snapshot(echoes.sum(), errors.sum())
                        end = after
                        println("END ${after.minus(before).wire()} ${peakThreads.get()}")
                        System.out.flush()
                    }

                    "DRAIN" -> {
                        val afterWindow = checkNotNull(end)
                        val afterDrain = snapshot(echoes.sum(), errors.sum())
                        println("DRAIN ${afterDrain.minus(afterWindow).wire()} ${peakThreads.get()}")
                        System.out.flush()
                    }

                    "STOP", null -> break
                }
            }
        } finally {
            stopping.set(true)
            sockets.forEach { it.close() }
            listener.close()
            sockets.filterIsInstance<VanillaPacketSocket>().forEach { it.awaitStopped() }
            runtime?.close()
            sampler.join(1000)
        }
    }

    /**
     * Matches ConnectionReceiveWorker/ConnectionSendWorker's TCP stream shape:
     * raw DataInputStream; BufferedOutputStream + DataOutputStream; flush each
     * 8-byte-header/body frame. Each connection has its own reader/writer.
     * Bounds are added to keep the benchmark safe, and never hit at window <=128.
     */
    private class VanillaPacketSocket(private val raw: Socket) : Socket(), NativePacketSocket {
        private data class Frame(val type: Int, val bytes: ByteArray)

        private val lock = Object()
        private val queue = ArrayDeque<Frame>()
        private var queuedBytes = 0L
        private val closed = AtomicBoolean()
        private var reader: Thread? = null
        private var writer: Thread? = null
        override val transportName = "VANILLA_TCP"

        init {
            raw.tcpNoDelay = true
        }

        override fun startReceiving(limit: () -> Int, receive: (Int, ByteArray) -> Unit, failure: (String) -> Unit) {
            check(reader == null && writer == null)
            writer = Thread({
                try {
                    val output = DataOutputStream(BufferedOutputStream(raw.getOutputStream()))
                    while (!closed.get()) {
                        val frame = synchronized(lock) {
                            while (queue.isEmpty() && !closed.get()) lock.wait(10_000)
                            if (closed.get()) null else queue.removeFirst()
                        } ?: break
                        output.writeInt(frame.bytes.size)
                        output.writeInt(frame.type)
                        output.write(frame.bytes)
                        output.flush()
                        synchronized(lock) { queuedBytes -= frame.bytes.size + 8L }
                    }
                } catch (problem: Exception) {
                    if (!closed.get()) failure(problem.message ?: "Vanilla writer failed")
                } finally {
                    close()
                }
            }, "SendWorker-benchmark-${raw.port}").apply { isDaemon = true; start() }
            reader = Thread({
                try {
                    val input = DataInputStream(raw.getInputStream())
                    while (!closed.get()) {
                        val size = input.readInt()
                        val type = input.readInt()
                        check(size >= 0 && size <= limit()) { "Invalid vanilla frame length: $size" }
                        val payload = ByteArray(size)
                        var offset = 0
                        while (offset < size) {
                            val read = input.read(payload, offset, size - offset)
                            check(read >= 0) { "EOF inside vanilla frame" }
                            offset += read
                        }
                        if (!closed.get()) receive(type, payload)
                    }
                } catch (problem: Exception) {
                    if (!closed.get()) failure(problem.message ?: "Vanilla reader failed")
                } finally {
                    close()
                }
            }, "ReceiveWorker-benchmark-${raw.port}").apply { isDaemon = true; start() }
        }

        override fun send(type: Int, bytes: ByteArray, delayAfterMillis: Int): Boolean = synchronized(lock) {
            if (closed.get() || queue.size >= 512 || queuedBytes + bytes.size + 8L > 64L * 1024 * 1024) return false
            // The native send queue owns Packet.data; it does not copy it here.
            queue.addLast(Frame(type, bytes))
            queuedBytes += bytes.size + 8L
            lock.notifyAll()
            true
        }

        override fun closeAfterWrites() = close() // The harness drains before closing.
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                raw.close()
            } finally {
                synchronized(lock) { queue.clear(); queuedBytes = 0; lock.notifyAll() }
                reader?.interrupt()
                writer?.interrupt()
            }
        }

        fun awaitStopped() {
            reader?.join(2000)
            writer?.join(2000)
            check(reader?.isAlive != true && writer?.isAlive != true) { "Vanilla worker did not stop" }
        }
    }

    private data class Snapshot(
        val nanos: Long, val cpu: Long, val messages: Long, val errors: Long,
        val gcCount: Long, val gcMillis: Long, val rss: Long, val heap: Long,
    ) {
        fun wire() = listOf(nanos, cpu, messages, errors, gcCount, gcMillis, rss, heap).joinToString(" ")
        fun minus(other: Snapshot) = copy(
            nanos = nanos - other.nanos, cpu = cpu - other.cpu,
            messages = messages - other.messages, errors = errors - other.errors,
            gcCount = gcCount - other.gcCount, gcMillis = gcMillis - other.gcMillis
        )
    }

    private fun snapshot(messages: Long, errors: Long): Snapshot {
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val rss = try {
            Regex("VmRSS:\\s+(\\d+) kB").find(Files.readString(Path.of("/proc/self/status")))
                ?.groupValues?.get(1)?.toLong()?.times(1024) ?: -1
        } catch (_: Exception) {
            -1
        }
        val memory = Runtime.getRuntime()
        return Snapshot(
            System.nanoTime(), cpuBean.processCpuTime, messages, errors,
            collectors.sumOf { max(0L, it.collectionCount) }, collectors.sumOf { max(0L, it.collectionTime) },
            rss, memory.totalMemory() - memory.freeMemory()
        )
    }

    private class Control {
        val phase = AtomicInteger()
        val stopping = AtomicBoolean()
        val failures = ConcurrentLinkedQueue<String>()
        @Volatile
        var measureDeadline = Long.MAX_VALUE
    }

    private class Slot(val buffer: ByteBuffer) {
        var busy = false
        var sequence = 0L
        var sent = 0L
        var phase = 0
    }

    private class Connection(val owner: Int, port: Int, val size: Int, val window: Int, selector: Selector) {
        val channel = SocketChannel.open().apply {
            configureBlocking(true)
            socket().tcpNoDelay = true
            connect(InetSocketAddress("127.0.0.1", port))
            configureBlocking(false)
        }
        val key = channel.register(selector, SelectionKey.OP_READ, this)
        val padding = ByteArray(size) { (owner * 31 + it * 17).toByte() }
        val slots = Array(window) {
            Slot(
                ByteBuffer.allocate(size + 8).apply { putInt(size); putInt(TYPE); put(padding); flip() })
        }
        val writes = ArrayDeque<Slot>()
        val gather = arrayOfNulls<ByteBuffer>(window)
        val incoming = ByteBuffer.allocate(max(64 * 1024, size + 8))
        var nextSequence = 0L
        var expectedSequence = 0L
        @Volatile
        var outstanding = 0
    }

    private class Worker(
        index: Int, peerIds: List<Int>, port: Int, size: Int, window: Int, private val control: Control,
    ) : Thread("benchmark-client-$index") {
        private val selector = Selector.open()
        val connections = peerIds.map { Connection(it, port, size, window, selector) }
        var measureSent = 0L
        var measureReceived = 0L
        var completedInWindow = 0L
        val latencyNanos = ArrayList<Long>()
        val errorCount = AtomicInteger()
        @Volatile
        var observedPhase = 0

        override fun run() {
            try {
                while (!control.stopping.get()) {
                    val phase = control.phase.get()
                    observedPhase = phase
                    for (connection in connections) {
                        fill(connection, phase)
                        write(connection)
                    }
                    selector.select(1)
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val connection = key.attachment() as Connection
                        if (key.isReadable) read(connection)
                        if (key.isWritable) write(connection)
                    }
                }
            } catch (problem: Exception) {
                if (!control.stopping.get()) {
                    errorCount.incrementAndGet()
                    control.failures.add("$name: ${problem.message}")
                    control.phase.set(0)
                }
            } finally {
                connections.forEach { it.channel.close() }
                selector.close()
            }
        }

        private fun fill(connection: Connection, phase: Int) {
            if (phase == 0) return
            while (connection.outstanding < connection.window) {
                val now = System.nanoTime()
                if (phase == 2 && now >= control.measureDeadline) return
                if (control.phase.get() != phase) return
                val sequence = connection.nextSequence++
                val slot = connection.slots[(sequence % connection.window).toInt()]
                check(!slot.busy) { "Overwrote an in-flight request" }
                slot.busy = true; slot.sequence = sequence; slot.sent = now; slot.phase = phase
                slot.buffer.clear()
                slot.buffer.putLong(8, sequence).putLong(16, now).putInt(24, phase).putInt(28, connection.owner)
                connection.writes.addLast(slot)
                connection.outstanding++
                if (phase == 2) measureSent++
            }
        }

        private fun write(connection: Connection) {
            if (connection.writes.isNotEmpty()) {
                var index = 0
                for (slot in connection.writes) connection.gather[index++] = slot.buffer
                @Suppress("UNCHECKED_CAST")
                connection.channel.write(connection.gather as Array<ByteBuffer>, 0, index)
                while (connection.writes.firstOrNull()?.buffer?.hasRemaining() == false) connection.writes.removeFirst()
            }
            connection.key.interestOps(SelectionKey.OP_READ or if (connection.writes.isEmpty()) 0 else SelectionKey.OP_WRITE)
        }

        private fun read(connection: Connection) {
            val buffer = connection.incoming
            check(connection.channel.read(buffer) >= 0) { "Server closed the connection" }
            buffer.flip()
            while (buffer.remaining() >= 8) {
                buffer.mark()
                val length = buffer.int
                val type = buffer.int
                check(length == connection.size && type == TYPE) { "Invalid response frame $length/$type" }
                if (buffer.remaining() < length) {
                    buffer.reset(); break
                }
                val offset = buffer.position()
                val sequence = buffer.getLong(offset)
                check(sequence == connection.expectedSequence++) { "Response sequence mismatch" }
                val slot = connection.slots[(sequence % connection.window).toInt()]
                check(
                    slot.busy && slot.sequence == sequence && buffer.getLong(offset + 8) == slot.sent &&
                            buffer.getInt(offset + 16) == slot.phase && buffer.getInt(offset + 20) == connection.owner
                ) { "Response identity mismatch" }
                check(
                    Arrays.mismatch(
                        buffer.array(), offset + PREFIX, offset + length,
                        connection.padding, PREFIX, length
                    ) == -1
                ) { "Response payload mismatch" }
                val receivedAt = System.nanoTime()
                if (slot.phase == 2) {
                    measureReceived++
                    if (receivedAt <= control.measureDeadline) completedInWindow++
                    if (connection.size >= 1_000_000 || shouldSampleLatency(sequence, connection.owner))
                        latencyNanos.add(receivedAt - slot.sent)
                }
                buffer.position(offset + length)
                slot.busy = false
                connection.outstanding--
            }
            buffer.compact()
        }
    }

    /** Stable ~1/32 sampling with mixed sequence and connection id to avoid window-period aliasing. */
    private fun shouldSampleLatency(sequence: Long, owner: Int): Boolean {
        var value = sequence xor (owner.toLong() * -7046029254386353131L)
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        value = value xor (value ushr 33)
        return (value and 31L) == 0L
    }

    private fun client(
        mode: String,
        peers: Int,
        size: Int,
        window: Int,
        seconds: Int,
        serverCpus: String,
        warmup: Int
    ): Map<String, Any?> {
        require(mode in setOf("optimized", "previous", "vanilla"))
        require(peers in 1..256 && size in PREFIX..2_000_000 && window in 1..128 && seconds in 1..60)
        require(warmup in 1..30)
        val inlineReceive = System.getProperty("rwpp.benchmark.inlineReceive") == "true"
        val command = mutableListOf<String>()
        if (System.getProperty("os.name").lowercase().contains("linux")) command.addAll(
            listOf(
                "taskset",
                "-c",
                serverCpus
            )
        )
        command.addAll(
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xms256m", "-Xmx1g",
                "-Drwpp.server.network.optimized=${mode == "optimized"}", "-cp", System.getProperty("java.class.path"),
                ServerThroughputBenchmark::class.java.name, "server", mode, peers.toString(), size.toString()
            )
        )
        val log = Files.createTempFile("rwpp-server-throughput-", ".log").toFile()
        val process = ProcessBuilder(command).redirectError(log).start()
        val reader = process.inputStream.bufferedReader()
        val writer = process.outputStream.bufferedWriter()
        val workers = ArrayList<Worker>()
        val control = Control()
        try {
            val port = response(reader, "READY").split(' ')[1].toInt()
            repeat(minOf(4, peers)) { index ->
                workers.add(
                    Worker(
                        index, (0 until peers).filter { it % minOf(4, peers) == index },
                        port, size, window, control
                    )
                )
            }
            workers.forEach { it.start() }
            fun stage(nanos: Long) {
                control.phase.set(1)
                waitUntil(System.nanoTime() + nanos)
                control.phase.set(0)
                check(drain(workers, control, 10)) { "Warmup failed to drain" }
            }
            stage(TimeUnit.SECONDS.toNanos(warmup.toLong()))
            stage(TimeUnit.SECONDS.toNanos(1))
            writer.write("BEGIN\n"); writer.flush()
            response(reader, "BEGIN")
            val clientCpuBefore = cpuBean.processCpuTime
            val started = System.nanoTime()
            control.measureDeadline = started + TimeUnit.SECONDS.toNanos(seconds.toLong())
            control.phase.set(2)
            waitUntil(control.measureDeadline)
            control.phase.set(0)
            val stopped = System.nanoTime()
            val clientCpuNanos = cpuBean.processCpuTime - clientCpuBefore
            writer.write("END\n"); writer.flush()
            val serverFixed = response(reader, "END").split(' ').drop(1).map { it.toLong() }
            val drainStarted = System.nanoTime()
            val drained = drain(workers, control, 10)
            val drainNanos = System.nanoTime() - drainStarted
            val clientCpuIncludingDrainNanos = cpuBean.processCpuTime - clientCpuBefore
            writer.write("DRAIN\n"); writer.flush()
            val serverDrain = response(reader, "DRAIN").split(' ').drop(1).map { it.toLong() }
            control.stopping.set(true)
            workers.forEach { it.join(2000) }
            check(workers.none { it.isAlive }) { "Client workers did not stop" }
            writer.write("STOP\n"); writer.flush()
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Server did not stop" }
            val elapsed = (stopped - started) / 1e9
            val sent = workers.sumOf { it.measureSent }
            val received = workers.sumOf { it.measureReceived }
            val completed = workers.sumOf { it.completedInWindow }
            val latencies = workers.flatMap { it.latencyNanos }.sorted()
            fun percentile(percent: Double): Double? = if (latencies.isEmpty()) null else
                latencies[((latencies.size - 1) * percent).toInt()] / 1e6

            val mib = size / (1024.0 * 1024)
            val serverCpuMillis = serverFixed[1] / 1e6
            val serverDrainCpuMillis = serverDrain[1] / 1e6
            val serverCpuIncludingDrainMillis = serverCpuMillis + serverDrainCpuMillis
            val clientCpuMillis = clientCpuNanos / 1e6
            val clientCpuIncludingDrainMillis = clientCpuIncludingDrainNanos / 1e6
            val clientDrainCpuMillis = clientCpuIncludingDrainMillis - clientCpuMillis
            val serverDrainErrors = serverDrain[3].toInt()
            val serverErrorsIncludingDrain = serverFixed[3].toInt() + serverDrainErrors
            val finalClientErrors = workers.sumOf { it.errorCount.get() }
            val processExit = process.exitValue()
            val fixedWindowErrors = finalClientErrors + serverFixed[3].toInt() + if (processExit == 0) 0 else 1
            val serverEchoesIncludingDrain = serverFixed[2] + serverDrain[2]
            val completeEchoes = received
            val echoCountMatchesClientCompletions = serverEchoesIncludingDrain == completeEchoes
            val errorsIncludingDrain = finalClientErrors + serverErrorsIncludingDrain +
                    (if (processExit == 0) 0 else 1) + (if (echoCountMatchesClientCompletions) 0 else 1) +
                    (if (drained) 0 else 1)
            val mibPerEcho = completeEchoes.toDouble().coerceAtLeast(1.0)
            return linkedMapOf(
                "mode" to mode, "connections" to peers, "payload_bytes" to size, "window" to window,
                "mode_definition" to when (mode) {
                    "vanilla" -> "native JDK receive/send worker stream model; legacy three-argument receive callback"
                    "previous" -> "production Netty with per-frame task scheduling"
                    else -> "production Netty with batched task scheduling"
                },
                "receive_mode" to if (inlineReceive) "bounded-inline" else "ordered-worker",
                "receive_mode_definition" to if (inlineReceive)
                    "bounded-inline profile: optimized Netty tries echo only for frames <= 64 KiB with an empty ordered receive queue; large frames, previous Netty, vanilla, and queued fallback stay on workers"
                else "all modes use the ordered worker callback path",
                "client_workers" to workers.size, "warmup_s" to warmup, "steady_s" to 1,
                "measure_elapsed_s" to elapsed, "server_measure_elapsed_s" to serverFixed[0] / 1e9,
                "drain_elapsed_s" to drainNanos / 1e9,
                "server_drain_elapsed_s" to serverDrain[0] / 1e9,
                "server_total_elapsed_s" to (serverFixed[0] + serverDrain[0]) / 1e9,
                "drained" to drained,
                "sent" to sent, "received" to received, "completed_in_window" to completed,
                "incomplete" to sent - received,
                "errors" to fixedWindowErrors,
                "errors_including_drain" to errorsIncludingDrain,
                "server_errors_in_drain" to serverDrainErrors,
                "server_errors_including_drain" to serverErrorsIncludingDrain,
                "server_echoes_including_drain" to serverEchoesIncludingDrain,
                "completed_echoes_after_drain" to completeEchoes,
                "echo_count_matches_client_completions" to echoCountMatchesClientCompletions,
                "client_cpu_ms_including_drain" to clientCpuIncludingDrainMillis,
                "client_drain_cpu_ms" to clientDrainCpuMillis,
                "server_cpu_ms_including_drain" to serverCpuIncludingDrainMillis,
                "server_drain_cpu_ms" to serverDrainCpuMillis,
                "client_cpu_ms_per_complete_echo_including_drain" to clientCpuIncludingDrainMillis / mibPerEcho,
                "server_cpu_ms_per_complete_echo_including_drain" to serverCpuIncludingDrainMillis / mibPerEcho,
                "combined_cpu_ms_per_complete_echo_including_drain" to
                        (clientCpuIncludingDrainMillis + serverCpuIncludingDrainMillis) / mibPerEcho,
                "messages_per_s" to completed / elapsed, "request_payload_mib_per_s" to completed * mib / elapsed,
                "echo_payload_mib_per_s" to completed * mib * 2 / elapsed,
                "server_echoes_in_cpu_window" to serverFixed[2], "server_cpu_ms" to serverCpuMillis,
                "client_cpu_ms" to clientCpuMillis,
                "server_cpu_ms_per_message" to serverCpuMillis / max(1L, serverFixed[2]),
                "client_cpu_ms_per_message" to clientCpuMillis / max(1L, completed),
                "server_cpu_ms_per_mib" to serverCpuMillis / max(1L, serverFixed[2]) / mib,
                "client_cpu_ms_per_mib" to clientCpuMillis / max(1L, completed) / mib,
                "p50_ms" to percentile(.50), "p99_ms" to percentile(.99), "latency_samples" to latencies.size,
                "latency_definition" to "client enqueue timestamp in fill() through echoed response identity/payload validation",
                "latency_sampling" to "all frames >= 1 MB; otherwise deterministic 64-bit mix of sequence and owner, low 5 bits zero (~1/32); measured requests including bounded drain",
                "goodput_definition" to "request payload completed during the CPU window; echo payload is twice this",
                "server_peak_threads" to serverFixed[8],
                "server_peak_threads_including_drain" to serverDrain[8],
                "server_rss_bytes" to serverFixed[6], "server_heap_bytes" to serverFixed[7],
                "server_gc_count" to serverFixed[4], "server_gc_ms" to serverFixed[5],
                "server_gc_count_including_drain" to (serverFixed[4] + serverDrain[4]),
                "server_gc_ms_including_drain" to (serverFixed[5] + serverDrain[5]),
                "server_exit" to processExit, "failure_detail" to control.failures.joinToString("; "),
                "java_version" to System.getProperty("java.version"),
            )
        } catch (problem: Exception) {
            return linkedMapOf(
                "mode" to mode,
                "connections" to peers,
                "payload_bytes" to size,
                "window" to window,
                "errors" to 1,
                "incomplete" to workers.sumOf { it.connections.sumOf { connection -> connection.outstanding.toLong() } },
                "failure_detail" to "${problem.message}; ${control.failures.joinToString()}; ${
                    log.readText().takeLast(5000)
                }"
            )
        } finally {
            control.phase.set(0)
            control.stopping.set(true)
            workers.forEach { it.join(2000) }
            if (process.isAlive) {
                try {
                    writer.write("STOP\n"); writer.flush()
                } catch (_: Exception) {
                }
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
                }
            }
            reader.close(); writer.close()
            Files.deleteIfExists(log.toPath())
        }
    }

    private fun drain(workers: List<Worker>, control: Control, seconds: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < deadline) {
            if (workers.all { worker -> worker.observedPhase == 0 && worker.connections.all { it.outstanding == 0 } }) return true
            if (control.failures.isNotEmpty()) return false
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        return false
    }

    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(remaining)
        }
    }

    private fun response(reader: BufferedReader, prefix: String): String = CompletableFuture.supplyAsync {
        while (true) {
            val line = reader.readLine() ?: error("Server exited before $prefix")
            if (line.startsWith("$prefix ")) return@supplyAsync line
        }
        @Suppress("UNREACHABLE_CODE") ""
    }.get(20, TimeUnit.SECONDS)

    private fun json(values: Map<String, Any?>): String = values.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> buildString {
                append('"')
                for (character in value.toString()) {
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (character.code < 32) append(
                            "\\u${
                                character.code.toString(16).padStart(4, '0')
                            }"
                        ) else append(character)
                    }
                }
                append('"')
            }
        }
    }
}
