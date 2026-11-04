@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadingCompositionLifecycleTest : DesktopComposeGraphicsTest() {
    @Test
    fun reloadOverlayKeepsTheScreenScopeMountedUntilTheDrawCompletes() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = queuedDispatcher(tasks)
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(640, 480))
        val surface = Surface.makeRasterN32Premul(640, 480)
        var loading by mutableStateOf(false)
        var keepContent by mutableStateOf(false)
        var mounted = 0
        var disposed = 0
        var scopeJobCancelled = false
        var backgroundClicks = 0
        var frameTime = 0L
        try {
            scene.setContent {
                MaterialTheme {
                    LoadingAwareContent(
                        isLoading = loading,
                        keepContentWhileLoading = keepContent,
                        loadingContent = { Box(Modifier.fillMaxSize()) { Text("LOADING") } },
                        content = {
                            val screenScope = rememberCoroutineScope()
                            DisposableEffect(Unit) {
                                mounted++
                                onDispose { disposed++ }
                            }
                            LaunchedEffect(screenScope) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    scopeJobCancelled = true
                                }
                            }
                            Box(Modifier.fillMaxSize().clickable { backgroundClicks++ }) { Text("MODS") }
                        },
                    )
                }
            }

            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertEquals(1, mounted)
            assertEquals(0, disposed)

            val generation = beginLoadingOverlayPresentation()
            keepContent = true
            loading = true
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            runBlocking { withTimeout(2_000) { awaitLoadingOverlayDrawn(generation) } }

            assertEquals(1, mounted)
            assertEquals(0, disposed)
            assertFalse(scopeJobCancelled, "the ModsScreen rememberCoroutineScope must survive the overlay")
            scene.sendPointerEvent(PointerEventType.Press, Offset(12f, 12f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(12f, 12f), button = PointerButton.Primary)
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertEquals(0, backgroundClicks, "the loading overlay must consume input")

            loading = false
            keepContent = false
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertEquals(0, disposed, "the original screen remains mounted after reload")
            assertFalse(scopeJobCancelled)
            finishLoadingOverlayPresentation(generation)
        } finally {
            scene.close()
            recomposer.close()
            // Compose 1.13 dispatches effect cancellation back to the host executor.
            while (true) (tasks.poll() ?: break).run()
            surface.close()
        }
        assertEquals(1, disposed)
        assertTrue(scopeJobCancelled, "the screen scope is cancelled only when the scene is disposed")
    }

    @Test
    fun initialLoadingDoesNotComposeTheAppUntilLoadingFinishes() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = queuedDispatcher(tasks)
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(320, 240))
        val surface = Surface.makeRasterN32Premul(320, 240)
        var mounted = 0
        var loading by mutableStateOf(true)
        var frameTime = 0L
        try {
            scene.setContent {
                LoadingAwareContent(
                    isLoading = loading,
                    keepContentWhileLoading = false,
                    loadingContent = { Text("STARTING") },
                    content = { DisposableEffect(Unit) { mounted++; onDispose { mounted-- } } },
                )
            }
            frameTime = drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertEquals(0, mounted)
            loading = false
            drawFrame(scene, recomposer, tasks, surface, frameTime)
            assertEquals(1, mounted)
        } finally {
            scene.close()
            recomposer.close()
            // Compose 1.13 dispatches effect cancellation back to the host executor.
            while (true) (tasks.poll() ?: break).run()
            surface.close()
        }
    }

    private fun queuedDispatcher(tasks: ConcurrentLinkedQueue<Runnable>) = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }
    }

    private fun drawFrame(
        scene: ComposeScene,
        recomposer: FrameRecomposer,
        tasks: ConcurrentLinkedQueue<Runnable>,
        surface: Surface,
        previousFrameTime: Long,
    ): Long {
        while (true) (tasks.poll() ?: break).run()
        val frameTime = previousFrameTime + 16_666_667L
        recomposer.performFrame(frameTime)
        while (true) (tasks.poll() ?: break).run()
        scene.measureAndLayout()
        scene.draw(surface.canvas.asComposeCanvas())
        return frameTime
    }
}
