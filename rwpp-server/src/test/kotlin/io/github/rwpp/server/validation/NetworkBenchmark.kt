package io.github.rwpp.api.net.impl

import com.sun.management.OperatingSystemMXBean
import io.github.rwpp.api.net.transport.TransportRuntime
import io.github.rwpp.server.net.NativeKcpServerSocket
import io.github.rwpp.server.net.NativeTcpServerSocket
import io.github.rwpp.server.net.NativeTcpSocket
import io.github.rwpp.server.net.ServerTransportBackend
import net.rudp.ReliableServerSocket
import net.rudp.ReliableSocket
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Opt-in transport benchmark. Both endpoints run in separate JVMs; no Gradle/JUnit timing. */
object NetworkBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (problem: Throwable) {
            problem.printStackTrace(); System.exit(2)
        }
    }

    private fun run(args: Array<String>) {
        TransportRuntime.install(ServerTransportBackend())
        if (args[0] == "server") {
            server(args[1]); return
        }
        val mode = args[0]
        val peers = args[1].toInt()
        val size = args[2].toInt()
        val hz = args[3].toInt()
        val seconds = args[4].toInt()
        val process = ProcessBuilder(
            java.io.File(System.getProperty("java.home"), "bin/java").path,
            "-Xms128m", "-Xmx512m", "-cp", System.getProperty("java.class.path"),
            NetworkBenchmark::class.java.name, "server", mode
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val lines = process.inputStream.bufferedReader()
        val commands = process.outputStream.bufferedWriter()
        fun line(prefix: String): String {
            while (true) {
                val value = lines.readLine() ?: error("Server stdout closed")
                if (value.startsWith(prefix)) return value.removePrefix(prefix)
            }
        }
        try {
            val port = line("PORT=").toInt()
            val latencies = ConcurrentLinkedQueue<Long>()
            val received = AtomicInteger()
            val errors = ConcurrentLinkedQueue<String>()
            val measureStart = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
            val connections = (0 until peers).map { index ->
                var expected = 0
                endpoint(mode, port) { type, bytes ->
                    if (type != 42 || bytes.size != size) errors.add("Invalid native frame")
                    val buffer = ByteBuffer.wrap(bytes)
                    val sequence = buffer.int
                    val sent = buffer.long
                    val owner = buffer.int
                    if (sequence != expected++ || owner != index || bytes.last() != 73.toByte()) errors.add("Order/data mismatch")
                    if (sent >= measureStart.get()) {
                        latencies.add(System.nanoTime() - sent); received.incrementAndGet()
                    }
                }
            }
            val sequences = IntArray(peers)
            var sentMessages = 0
            fun load(durationSeconds: Int, measured: Boolean) {
                val start = System.nanoTime()
                val ticks = durationSeconds * hz
                for (tick in 0 until ticks) {
                    val deadline = start + tick * 1_000_000_000L / hz
                    while (System.nanoTime() < deadline) LockSupport.parkNanos(
                        (deadline - System.nanoTime()).coerceAtMost(
                            1_000_000
                        )
                    )
                    for (index in connections.indices) {
                        val payload = ByteArray(size)
                        ByteBuffer.wrap(payload).putInt(sequences[index]++).putLong(System.nanoTime()).putInt(index)
                        payload[payload.lastIndex] = 73
                        connections[index].send(42, payload)
                        if (measured) sentMessages++
                    }
                }
            }
            load(3, false)
            Thread.sleep(1000)
            commands.write("START\n"); commands.flush(); line("READY")
            val threads = ManagementFactory.getThreadMXBean()
            threads.resetPeakThreadCount()
            val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val cpu = os.processCpuTime
            measureStart.set(System.nanoTime())
            load(seconds, true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (received.get() < sentMessages && System.nanoTime() < deadline) Thread.sleep(2)
            val elapsed = System.nanoTime() - measureStart.get()
            val cpuMs = (os.processCpuTime - cpu) / 1_000_000.0
            commands.write("STATS\n"); commands.flush()
            val server = line("SERVER=")
            val sorted = latencies.toList().sorted()
            fun percentile(p: Double): Double =
                if (sorted.isEmpty()) -1.0 else sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
            println(
                "{\"mode\":\"$mode\",\"peers\":$peers,\"payload_bytes\":$size,\"hz\":$hz," +
                        "\"sent\":$sentMessages,\"received\":${received.get()},\"errors\":${errors.size}," +
                        "\"p50_ms\":${percentile(.50)},\"p95_ms\":${percentile(.95)},\"p99_ms\":${percentile(.99)}," +
                        "\"max_ms\":${percentile(1.0)},\"elapsed_s\":${elapsed / 1e9},\"client_cpu_ms\":$cpuMs," +
                        "\"client_peak_threads\":${threads.peakThreadCount},\"server\":$server}"
            )
            connections.forEach { it.close() }
            check(received.get() == sentMessages && errors.isEmpty()) { "Packets were lost, reordered or corrupted" }
            commands.write("STOP\n"); commands.flush()
            process.waitFor(5, TimeUnit.SECONDS)
        } finally {
            process.destroyForcibly()
        }
        System.exit(0)
    }

    private interface Endpoint : AutoCloseable {
        fun send(type: Int, bytes: ByteArray)
    }

    private fun endpoint(mode: String, port: Int, receive: (Int, ByteArray) -> Unit): Endpoint {
        val address = InetSocketAddress("127.0.0.1", port)
        val socket: Socket = when (mode) {
            "vanilla-tcp" -> Socket().apply { tcpNoDelay = true; connect(address, 5000) }
            "vanilla-rudp", "vanilla-rudp-double-send" -> ReliableSocket().apply { connect(address, 5000) }
            "netty-tcp" -> NativeTcpSocket().apply { connect(address, 5000) }
            "kcp" -> NativeKcpSocket().apply { connect(address, 5000); check(transportName == "KCP") }
            else -> error(mode)
        }
        return wrap(socket, mode == "vanilla-rudp-double-send", receive)
    }

    private fun wrap(socket: Socket, doubleSend: Boolean, receive: (Int, ByteArray) -> Unit): Endpoint {
        if (socket is NativePacketSocket && socket.handlesNativePackets) {
            socket.startReceiving({ NativeTcpSocket.MAX_PACKET_BYTES }, receive, {})
            return object : Endpoint {
                override fun send(type: Int, bytes: ByteArray) {
                    check(socket.send(type, bytes))
                }

                override fun close() = socket.close()
            }
        }
        // Mirrors ConnectionReceiveWorker and ConnectionSendWorker, including UDP's single-frame write.
        val pending = ArrayBlockingQueue<Pair<Int, ByteArray>>(512)
        thread(name = "vanilla-send", isDaemon = true) {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val scratch = ByteArray(508)
            try {
                while (!socket.isClosed) {
                    val (type, bytes) = pending.take()
                    if (socket is ReliableSocket) {
                        val frame = if (bytes.size <= 500) scratch else ByteArray(bytes.size + 8)
                        ByteBuffer.wrap(frame).putInt(bytes.size).putInt(type).put(bytes)
                        // false still uses ACK/retransmission. true immediately transmits each DAT twice,
                        // as the original game's special type-10 step packets do (Packet defaults false).
                        socket.sendData(frame, 0, bytes.size + 8, doubleSend)
                    } else {
                        output.writeInt(bytes.size); output.writeInt(type); output.write(bytes); output.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        thread(name = "vanilla-receive", isDaemon = true) {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (!socket.isClosed) {
                    val size = input.readInt();
                    val type = input.readInt()
                    require(size in 0..NativeTcpSocket.MAX_PACKET_BYTES)
                    receive(type, ByteArray(size).also(input::readFully))
                }
            } catch (_: Exception) {
            }
        }
        return object : Endpoint {
            override fun send(type: Int, bytes: ByteArray) {
                check(pending.offer(type to bytes))
            }

            override fun close() = socket.close()
        }
    }

    private fun server(mode: String) {
        val listener: ServerSocket = when (mode) {
            "vanilla-tcp" -> ServerSocket(0)
            "vanilla-rudp", "vanilla-rudp-double-send" -> ReliableServerSocket(0, 0, null, true)
            "netty-tcp" -> NativeTcpServerSocket(0)
            "kcp" -> NativeKcpServerSocket(0)
            else -> error(mode)
        }
        val endpoints = CopyOnWriteArrayList<Endpoint>()
        thread(name = "benchmark-accept", isDaemon = true) {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    socket.tcpNoDelay = true
                    val ready = CompletableFuture<Endpoint>()
                    val endpoint = wrap(socket, mode == "vanilla-rudp-double-send") { type, bytes ->
                        ready.join().send(type, bytes)
                    }
                    ready.complete(endpoint)
                    endpoints.add(endpoint)
                }
            } catch (_: Exception) {
            }
        }
        println("PORT=${listener.localPort}")
        val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val threads = ManagementFactory.getThreadMXBean()
        var cpu = 0L
        var gc = 0L
        var networkBytes = 0L
        fun transmitted() = runCatching {
            File("/proc/net/dev").readLines().first { it.trimStart().startsWith("lo:") }
                .substringAfter(':').trim().split(Regex("\\s+"))[8].toLong()
        }.getOrDefault(-1)
        while (true) {
            when (readlnOrNull()) {
                "START" -> {
                    cpu = os.processCpuTime; threads.resetPeakThreadCount()
                    networkBytes = transmitted()
                    gc = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
                    println("READY")
                }

                "STATS" -> {
                    val rss = runCatching {
                        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }
                            .split(Regex("\\s+"))[1].toLong()
                    }.getOrDefault(-1)
                    val gcMs =
                        ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) } - gc
                    println("SERVER={\"cpu_ms\":${(os.processCpuTime - cpu) / 1e6},\"peak_threads\":${threads.peakThreadCount},\"rss_kib\":$rss,\"gc_ms\":$gcMs,\"loopback_tx_bytes\":${transmitted() - networkBytes}}")
                }

                "STOP", null -> break
            }
        }
        endpoints.forEach { it.close() }; listener.close()
        System.exit(0)
    }
}
