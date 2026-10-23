package io.github.rwpp.server

import com.corrodinggames.rts.game.RWGameEngine
import io.github.rwml.api.Extension
import io.github.rwpp.api.ApiRuntime
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A server extension may supply the process's network/simulation loop instead of vanilla hosting.
 * RWML loads all extensions before this method runs on the owning game thread. Only one extension
 * may implement this contract. Returning (or throwing) shuts down the host and all extensions.
 */
interface ServerExtension : Extension {
    fun runServer(context: ServerRuntimeContext)

    /** Called from the JVM shutdown hook; must only signal the owning loop to stop. */
    fun requestStop()
}

class ServerRuntimeContext internal constructor(
    val directory: Path,
    val arguments: List<String>,
    private val running: AtomicBoolean,
) {
    val isRunning: Boolean get() = running.get()

    /** Create the native engine once, after every extension's mixins have been registered. */
    fun createEngine(noMods: Boolean = false): RWGameEngine = HeadlessEngine.create(noMods)

    /** Drain shared API work on the game thread once per custom frame, including lobby frames. */
    fun pump() = ApiRuntime.pumpHeadlessFrame()

    /** Run a suspend loop without blocking coroutine continuations targeting the game thread. */
    fun runOnGameThread(block: suspend () -> Unit) = ApiRuntime.runHeadlessLoop(block)
}

/** Built-in dependency identity used by server extension descriptors. */
class ServerServices : Extension
