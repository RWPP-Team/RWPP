package io.github.rwpp.server

import io.github.rwpp.api.coroutines.GameMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertSame

class ServerGameThreadTest {
    @Test
    fun suspendedServerCallbacksCanReturnToTheOwningGameThread() {
        val owner = Thread.currentThread()
        val context = ServerRuntimeContext(Path.of("."), emptyList(), AtomicBoolean(true))
        context.runOnGameThread {
            withContext(Dispatchers.Default) { delay(10) }
            assertSame(owner, Thread.currentThread())
            withContext(GameMainDispatcher) {
                assertSame(owner, Thread.currentThread())
            }
        }
    }
}
