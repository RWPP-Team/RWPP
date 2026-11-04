package io.github.rwpp.ui

import io.github.rwpp.sound.UiAudioQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class UiAudioQueueTest {
    @Test
    fun blockedAudioBackendNeverBlocksTheCallerAndKeepsOnlyTheNewestRequest() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1);
        val release = CountDownLatch(1);
        val finished = CountDownLatch(1)
        val backendThread = AtomicReference<Thread>()
        val played = AtomicInteger()
        val queue = UiAudioQueue(scope, intervalNanos = 0)
        try {
            queue.submit { backendThread.set(Thread.currentThread()); entered.countDown(); release.await() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // These calls must return while the backend is still blocked.
            repeat(1000) { queue.submit { played.incrementAndGet() } }
            queue.submit { played.addAndGet(100); finished.countDown() }
            assertEquals(0, played.get())
            assertNotSame(Thread.currentThread(), backendThread.get())
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(100, played.get(), "Old hover/click requests must not accumulate")
        } finally {
            release.countDown(); scope.cancel()
        }
    }

    @Test
    fun backendFailureDoesNotDisableFollowingSounds() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failed = CountDownLatch(1);
        val finished = CountDownLatch(1)
        val queue = UiAudioQueue(scope, intervalNanos = 0, onFailure = { failed.countDown() })
        try {
            queue.submit { error("Unavailable audio device") }
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            queue.submit { finished.countDown() }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recentHoverDoesNotMuteAnExplicitButtonClick() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hovered = CountDownLatch(1);
        val clicked = CountDownLatch(1)
        val queue = UiAudioQueue(scope, intervalNanos = Long.MAX_VALUE)
        try {
            queue.submit { hovered.countDown() }
            assertTrue(hovered.await(3, TimeUnit.SECONDS))
            queue.submit(throttle = false) { clicked.countDown() }
            assertTrue(clicked.await(3, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
        }
    }
}
