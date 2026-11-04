package io.github.rwpp.api.game.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSocketCompletionDispatchTest {
    @Test
    fun socketWorkerCompletionWaitsForTheGameFrameQueue() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        var startedConnection = false

        try {
            dispatchNativeSocketCompletion(scope, dispatcher) {
                startedConnection = true
            }

            assertFalse(startedConnection, "startClientConnection must not run on the socket worker")
            dispatcher.drain()
            assertTrue(startedConnection, "completion should run once the game-thread queue drains")
        } finally {
            scope.cancel()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
