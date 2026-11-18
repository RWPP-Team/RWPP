package io.github.rwpp.api.game.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkClientReadyAckTest {
    private var now = 0L
    private val ack = NetworkClientReadyAck { now }

    @Test
    fun mapLoadAndActiveLoopAreRequiredBeforeCountingNativeReady() {
        ack.begin(isNetworkedClient = true)
        assertFalse(ack.beginAttempt())
        assertFalse(ack.onReadyStateSent(gameLoopActive = true))

        ack.markMapLoaded()
        assertFalse(ack.onReadyStateSent(gameLoopActive = false))
        assertTrue(ack.beginAttempt())
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))
        assertFalse(ack.isPending())
    }

    @Test
    fun localSendWithoutServerProgressRetriesAtMostTwice() {
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))

        repeat(2) {
            now += 1_499_000_000
            assertFalse(ack.beginAttempt())
            now += 1_000_000
            assertTrue(ack.beginAttempt())
            assertFalse(ack.onReadyStateSent(gameLoopActive = true))
        }
        now += 1_500_000_000
        assertFalse(ack.beginAttempt())
    }

    @Test
    fun serverProgressStopsRetriesAfterTheFirstSend() {
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertFalse(ack.stopRetriesAfterServerStep())
        assertTrue(ack.beginAttempt())
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))
        assertTrue(ack.stopRetriesAfterServerStep())
        now += 1_500_000_000
        assertFalse(ack.beginAttempt())
    }

    @Test
    fun eachMatchHasItsOwnReadyState() {
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))
        ack.clear()
        assertFalse(ack.canCheckReady())

        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.beginAttempt())
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))
    }

    @Test
    fun nonClientSessionsNeverSendReady() {
        ack.begin(isNetworkedClient = false)
        ack.markMapLoaded()
        assertFalse(ack.beginAttempt())
    }

    @Test
    fun failedLocalAttemptsDoNotUseTheSuccessfulSendLimit() {
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        repeat(12) {
            assertTrue(ack.beginAttempt())
            assertFalse(ack.beginAttempt())
            now += 500_000_000
        }
        assertTrue(ack.isPending())
        assertTrue(ack.onReadyStateSent(gameLoopActive = true))
    }
}
