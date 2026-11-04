package io.github.rwpp.api.game.map

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplayTimestampTest {
    @Test
    fun extractsNativeReplayFilenameTimestamp() {
        assertNotNull(Replay(0, "Coastline [1.16] (1 Jan 2025 12.30.45).replay").timestampMillis)
    }

    @Test
    fun returnsNullWhenFilenameHasNoRecognizedTimestamp() {
        assertNull(Replay(0, "manual recording.replay").timestampMillis)
    }
}
