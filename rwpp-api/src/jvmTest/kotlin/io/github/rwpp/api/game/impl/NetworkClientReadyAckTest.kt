package io.github.rwpp.api.game.impl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkClientReadyAckTest {
    /** Drains the confirmation cooldown that separates two sends. */
    private fun NetworkClientReadyAck.attemptAfterCooldown(): Boolean {
        repeat(200) {
            if (beginAttemptIfReady(true, true, true, true)) return true
        }
        return false
    }

    @Test
    fun waitsUntilLoadedClientCanRunThenSends() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)

        assertFalse(ack.beginAttemptIfReady(true, false, false, false))
        assertFalse(ack.beginAttemptIfReady(true, true, false, false))
        assertFalse(ack.beginAttemptIfReady(true, true, true, false))

        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true))
    }

    @Test
    fun eachNewNetworkStartGetsItsOwnAckAndFailedLoadsClearThePendingAck() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true))
        ack.markSent()

        // Returning to the room and receiving packet 120 for another match starts a fresh cycle.
        ack.clear()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        assertTrue(ack.beginAttemptIfReady(true, true, true, true))

        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()
        ack.clear() // Native map-load failure or disconnect.
        assertFalse(ack.beginAttemptIfReady(true, true, true, true))
    }

    @Test
    fun hostSinglePlayerAndReplayNeverTakeAClientReadyAck() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = false)

        assertFalse(ack.beginAttemptIfReady(false, true, true, true))
        assertFalse(ack.beginAttemptIfReady(false, true, true, true))
    }

    @Test
    fun failedNativeSendsKeepRetryingWhileTheClientIsLoading() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()

        // A disconnected or still-attaching client must not exhaust the handshake; the host keeps
        // waiting until packet 112 reports gameLoopActive.
        repeat(12) {
            assertTrue(ack.attemptAfterCooldown())
            assertTrue(ack.markAttemptFailed())
        }
        assertTrue(ack.isPending())
    }

    @Test
    fun confirmationResendsAreBoundedAndThenStop() {
        val ack = NetworkClientReadyAck()
        ack.begin(isNetworkedClient = true)
        ack.markMapLoaded()

        assertTrue(ack.beginAttemptIfReady(true, true, true, true))
        ack.markSent()
        assertTrue(ack.isPending())
        assertTrue(ack.attemptAfterCooldown())
        ack.markSent()
        assertTrue(ack.attemptAfterCooldown())
        ack.markSent()

        assertFalse(ack.isPending())
        assertFalse(ack.beginAttemptIfReady(true, true, true, true))
    }
}
