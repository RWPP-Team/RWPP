package io.github.rwpp.server.net

import io.github.rwpp.api.net.transport.*
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TransportPressureTest {
    @Test fun fullCallbackMailboxStillDeliversOneFinalDisconnectAfterAllFrames() {
        val tasks = ArrayDeque<Runnable>()
        var failures = 0
        val queue = CallbackQueue(Executor { tasks.add(it) }) { failures++ }
        val events = mutableListOf<Int>()
        repeat(512) { index -> assertTrue(queue.add { events += index }) }
        assertFalse(queue.add { fail("Overfull callback was accepted") })
        assertTrue(queue.addTerminal { events += -1 })
        assertFalse(queue.addTerminal { fail("Duplicate terminal callback") })
        assertFalse(queue.add { fail("Callback after disconnect") })
        while (tasks.isNotEmpty()) tasks.remove().run()
        assertEquals((0..511).toList() + -1, events)
        assertEquals(1, failures)
    }

    @Test fun callbackErrorsAndExecutorRejectionCannotLoseTerminalCleanup() {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        val executor = Executor { if (reject) throw RejectedExecutionException() else tasks.add(it) }
        var failures = 0
        var delivered = 0
        var terminals = 0
        val queue = CallbackQueue(executor) { failures++ }
        repeat(128) { index -> queue.add { if (index == 10) throw AssertionError("Application callback"); delivered++ } }
        queue.addTerminal { terminals++ }
        reject = true
        tasks.remove().run()
        assertEquals(127, delivered)
        assertEquals(1, terminals)
        assertTrue(failures >= 1)
    }

    @Test fun localUdpQueuePressureIsRetransmittedWithoutDisconnectingTheKcpPeer() {
        val serverRuntime = ServerTransportBackend()
        val clientRuntime = ServerTransportBackend()
        val config = TransportConfig(port = 0, conv = 0x33445566)
        val server = NettyKcpServerTransport(serverRuntime, config)
        var client: ClientTransport? = null
        val resumeIo = CountDownLatch(1)
        DatagramSocket(0).use { discard ->
            try {
                val peerReady = CompletableFuture<TransportPeer>()
                val disconnected = AtomicInteger()
                server.start(object : TransportListener {
                    override fun connected(peer: TransportPeer) { peerReady.complete(peer) }
                    override fun received(peer: TransportPeer, payload: ByteArray) { peer.trySend(payload) }
                    override fun disconnected(peer: TransportPeer) { disconnected.incrementAndGet() }
                })
                val firstEcho = CountDownLatch(1)
                val recovered = CompletableFuture<ByteArray>()
                val failure = CompletableFuture<String>()
                client = clientRuntime.kcpClient(config.copy(port = server.localAddress!!.port), "127.0.0.1", TransportMessages.DEFAULT)
                val link = client.connect(object : ClientSink {
                    override fun message(payload: ByteArray) { if (payload.size == 1) firstEcho.countDown() else recovered.complete(payload) }
                    override fun failure(reason: String) { failure.complete(reason) }
                })
                assertTrue(link.send(byteArrayOf(1)))
                assertTrue(firstEcho.await(3, TimeUnit.SECONDS))
                val peer = peerReady.get(3, TimeUnit.SECONDS)
                val io = serverRuntime.io.toList()
                val paused = CountDownLatch(io.size)
                io.forEach { executor -> executor.execute { paused.countDown(); resumeIo.await(10, TimeUnit.SECONDS) } }
                assertTrue(paused.await(3, TimeUnit.SECONDS))
                val field = NettyKcpServerTransport::class.java.getDeclaredField("writer").apply { isAccessible = true }
                val writer = field.get(server) as DatagramWriter
                val target = InetSocketAddress("127.0.0.1", discard.localPort)
                val tiny = byteArrayOf(0)
                while (writer.send(target, tiny)) Unit
                val rejected = writer.rejectedDatagrams.get()
                val payload = ByteArray(1024) { it.toByte() }
                assertTrue(peer.trySend(payload))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (writer.rejectedDatagrams.get() == rejected && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(writer.rejectedDatagrams.get() > rejected, "KCP output did not reach the full local UDP queue")
                assertEquals(1, server.peerCount)
                assertEquals(0, disconnected.get())
                resumeIo.countDown()
                assertContentEquals(payload, recovered.get(6, TimeUnit.SECONDS))
                assertFalse(failure.isDone)
                assertEquals(1, server.peerCount)
                assertEquals(0, disconnected.get())
            } finally {
                resumeIo.countDown()
                client?.close(); server.close(); clientRuntime.close(); serverRuntime.close()
            }
        }
    }
}
