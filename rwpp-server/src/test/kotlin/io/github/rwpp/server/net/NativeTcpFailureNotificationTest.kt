package io.github.rwpp.server.net

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.EventExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTcpFailureNotificationTest {
    @Test
    fun failureCallbackNeverInheritsTheSocketMonitor() {
        for (optimized in listOf(true, false)) fixture(optimized) { test ->
            val notified = CompletableFuture<Boolean>()
            test.socket.startReceiving({ 1024 }, { _, _ -> }, {
                notified.complete(Thread.holdsLock(test.socket))
            })
            synchronized(test.socket) {
                test.fail("socket monitor test")
                assertFalse(notified.isDone, "Failure notification crossed the held socket barrier")
            }
            assertFalse(notified.get(3, TimeUnit.SECONDS), "Application failure callback held the socket monitor")
        }
    }

    @Test
    fun failureNotificationWaitsForTheInFlightReceiverToFinish() {
        for (optimized in listOf(true, false)) fixture(optimized) { test ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val notified = CountDownLatch(1)
            val calls = AtomicInteger()
            val heldMonitor = AtomicBoolean(true)
            val events = ConcurrentLinkedQueue<String>()
            test.socket.startReceiving({ 1024 }, { _, _ ->
                events.add("receive-start")
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Receiver was not released" }
                events.add("receive-end")
            }, {
                heldMonitor.set(Thread.holdsLock(test.socket))
                calls.incrementAndGet()
                events.add("failure")
                notified.countDown()
            })
            try {
                test.frame()
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Receive callback did not begin")
                synchronized(test.socket) { test.fail("in-flight test") }
                assertFalse(notified.await(100, TimeUnit.MILLISECONDS), "Failure overtook the active receiver")
                assertEquals(listOf("receive-start"), events.toList())
                release.countDown()
                assertTrue(notified.await(3, TimeUnit.SECONDS), "Failure was lost after the receiver completed")
                assertEquals(listOf("receive-start", "receive-end", "failure"), events.toList())
                assertFalse(heldMonitor.get())
                test.fail("duplicate failure")
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun rejectedPacketExecutorStillNotifiesExactlyOnceOutsideTheSocketMonitor() = fixture(true) { test ->
        val notified = CountDownLatch(1)
        val calls = AtomicInteger()
        val heldMonitor = AtomicBoolean(true)
        test.socket.startReceiving({ 1024 }, { _, _ -> }, {
            heldMonitor.set(Thread.holdsLock(test.socket))
            calls.incrementAndGet()
            notified.countDown()
        })
        // Use a genuinely terminated executor rather than a fake direct executor:
        // the production rejection path must preserve its asynchronous barrier.
        test.executor.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly()
        assertTrue(test.executor.isTerminated)
        synchronized(test.socket) {
            test.fail("packet executor rejected")
            test.socket.close()
            assertEquals(1L, notified.count, "Fallback ran application teardown under the caller's monitor")
        }
        assertTrue(notified.await(3, TimeUnit.SECONDS), "Rejected executor lost failure notification")
        assertFalse(heldMonitor.get())
        test.fail("duplicate after rejection")
        test.socket.close()
        test.channel.runPendingTasks()
        assertEquals(1, calls.get())
    }

    @Test
    fun closePreservesAnAlreadyRegisteredFailureBehindTheWorkerQueue() = fixture(true) { test ->
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val notified = CompletableFuture<String>()
        val calls = AtomicInteger()
        test.socket.startReceiving({ 1024 }, { _, _ -> }, { reason ->
            calls.incrementAndGet()
            notified.complete(reason)
        })
        test.executor.execute {
            workerEntered.countDown()
            check(releaseWorker.await(5, TimeUnit.SECONDS)) { "Packet worker was not released" }
        }
        try {
            assertTrue(workerEntered.await(3, TimeUnit.SECONDS))
            synchronized(test.socket) {
                test.fail("registered before close")
                test.socket.close()
            }
            assertFalse(notified.isDone)
            releaseWorker.countDown()
            assertEquals("registered before close", notified.get(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            releaseWorker.countDown()
        }
    }

    private class Fixture(runtime: ServerTransportBackend) {
        val channel = EmbeddedChannel()
        val socket = NativeTcpSocket(runtime, channel)
        val executor = NativeTcpSocket::class.java.getDeclaredField("executor").let {
            it.isAccessible = true
            it.get(socket) as EventExecutor
        }

        fun frame() {
            channel.writeInbound(Unpooled.buffer(9).writeInt(1).writeInt(160).writeByte(7))
            channel.runPendingTasks()
        }

        fun fail(reason: String) {
            // Deterministic fault injection reproduces callers such as send queue
            // overflow and write completion, which already hold this monitor.
            NativeTcpSocket::class.java.getDeclaredMethod("fail", String::class.java).apply {
                isAccessible = true
            }.invoke(socket, reason)
        }
    }

    private fun fixture(optimized: Boolean, block: (Fixture) -> Unit) {
        val previous = System.getProperty("rwpp.server.network.optimized")
        val runtime = try {
            System.setProperty("rwpp.server.network.optimized", optimized.toString())
            ServerTransportBackend()
        } finally {
            if (previous == null) System.clearProperty("rwpp.server.network.optimized")
            else System.setProperty("rwpp.server.network.optimized", previous)
        }
        val test = Fixture(runtime)
        try {
            block(test)
        } finally {
            test.socket.close()
            test.channel.finishAndReleaseAll()
            runtime.close()
        }
    }
}
