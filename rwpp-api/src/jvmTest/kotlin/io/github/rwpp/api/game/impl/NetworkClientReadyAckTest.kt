package io.github.rwpp.api.game.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkClientReadyAckTest {
    @Test
    fun waitsUntilLoadedClientCanRunThenSendsOnlyOnce() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)

        assertFalse(ack.beginAttemptIfReady(true, false, false, false, false))
        assertFalse(ack.beginAttemptIfReady(true, true, false, false, true))
        assertFalse(ack.beginAttemptIfReady(true, true, true, false, true))
        assertFalse(ack.beginAttemptIfReady(true, true, true, true, false))

        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true, true))
        ack.markSent()
        assertFalse(ack.beginAttemptIfReady(true, true, true, true, true))
    }

    @Test
    fun eachNewNetworkStartGetsOneAckAndFailedLoadsClearThePendingAck() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true, true))
        ack.markSent()

        // Returning to the room and receiving packet 120 for another match starts a fresh cycle.
        ack.clear()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true, true))

        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        ack.clear() // Native map-load failure or disconnect.
        assertFalse(ack.beginAttemptIfReady(true, true, true, true, true))
    }

    @Test
    fun hostSinglePlayerAndReplayNeverTakeAClientReadyAck() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = false)

        assertFalse(ack.beginAttemptIfReady(false, true, true, true, true))
        assertFalse(ack.beginAttemptIfReady(false, true, true, true, true))
    }

    @Test
    fun failedNativeSendsAreRetriedOnlyThreeTimes() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()

        repeat(2) {
            assertTrue(ack.beginAttemptIfReady(true, true, true, true, true))
            assertTrue(ack.markAttemptFailed())
        }
        assertTrue(ack.beginAttemptIfReady(true, true, true, true, true))
        assertFalse(ack.markAttemptFailed())
        assertFalse(ack.beginAttemptIfReady(true, true, true, true, true))
    }
}
