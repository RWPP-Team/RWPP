package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sun.management.ThreadMXBean
import io.github.rwpp.api.logging.logger
import io.github.rwpp.desktop.OffscreenComposeRenderer
import kotlinx.coroutines.CompletableDeferred
import java.lang.management.ManagementFactory
import java.util.Locale

internal object DesktopScopeTreeMetrics {
    private const val WarmupFrames = 90
    private const val SampleFrames = 180
    private const val PhaseFrames = WarmupFrames + SampleFrames
    private val mode = System.getProperty("rwpp.scopeTreePerf")
    private val densityStep = mutableIntStateOf(0)
    private val complete = CompletableDeferred<Unit>()
    private val allocations by lazy {
        (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
        }
    }
    private val elapsed = LongArray(SampleFrames)
    private val allocated = LongArray(SampleFrames)
    private var frame = 0

    @Composable
    fun Content(content: @Composable () -> Unit) {
        if (mode == null) {
            content()
            return
        }
        val density = LocalDensity.current
        val step = densityStep.intValue
        val effective = if (step == 0) density else Density(density.density * 1.025f, density.fontScale)
        CompositionLocalProvider(LocalDensity provides effective, content = content)
    }

    fun mount(action: () -> Unit) {
        if (mode == null) {
            action()
            return
        }
        val start = System.nanoTime()
        action()
        logger().info("ScopeTree perf mode=$mode mountMs=${milliseconds(System.nanoTime() - start)}")
    }

    fun render(scene: OffscreenComposeRenderer) {
        if (mode == null || frame >= PhaseFrames * 2) {
            scene.render()
            return
        }
        val stress = frame >= PhaseFrames
        val phaseFrame = frame % PhaseFrames
        if (stress) densityStep.intValue = (phaseFrame + 1) % 2
        val meter = allocations
        val previousBytes = meter?.currentThreadAllocatedBytes ?: 0L
        val start = System.nanoTime()
        scene.render()
        if (phaseFrame >= WarmupFrames) {
            val index = phaseFrame - WarmupFrames
            elapsed[index] = System.nanoTime() - start
            allocated[index] = (meter?.currentThreadAllocatedBytes ?: 0L) - previousBytes
        }
        frame++
        if (phaseFrame == PhaseFrames - 1) {
            elapsed.sort()
            allocated.sort()
            val phase = if (stress) "density-change" else "idle"
            logger().info(
                "ScopeTree perf mode=$mode phase=$phase frames=$SampleFrames " +
                    "p50Ms=${milliseconds(elapsed[SampleFrames / 2])} " +
                    "p95Ms=${milliseconds(elapsed[SampleFrames * 95 / 100 - 1])} " +
                    "allocationP50Bytes=${if (meter == null) "unavailable" else allocated[SampleFrames / 2]}"
            )
            if (stress) complete.complete(Unit)
        }
    }

    suspend fun awaitCompletion() {
        if (mode != null) complete.await()
    }

    private fun milliseconds(nanos: Long): String =
        String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
}
