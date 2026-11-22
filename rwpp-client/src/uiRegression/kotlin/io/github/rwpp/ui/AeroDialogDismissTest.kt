package io.github.rwpp.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.widget.v2.AnimatedAlertDialog
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class AeroDialogDismissTest : DesktopComposeGraphicsTest() {
    @Test
    fun completedTransferDismissesWithTheLatestCallback() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(360, 240))
        val visible = mutableStateOf(true)
        val complete = mutableStateOf(false)
        var frameTime = 0L

        Surface.makeRasterN32Premul(360, 240).use { surface ->
            fun renderFrame() {
                dispatcher.drain()
                frameTime += 16_666_667L
                recomposer.performFrame(frameTime)
                dispatcher.drain()
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }

            try {
                scene.setContent {
                    val canClose = complete.value
                    AnimatedAlertDialog(visible.value, onDismissRequest = { if (canClose) visible.value = false }) { dismiss ->
                        LaunchedEffect(canClose) { if (canClose) dismiss() }
                        Text("Transfer")
                    }
                }
                repeat(4) { renderFrame() }
                assertTrue(visible.value)
                complete.value = true

                val deadline = System.nanoTime() + 2_000_000_000L
                while (visible.value && System.nanoTime() < deadline) {
                    renderFrame()
                    Thread.sleep(8)
                }
                assertFalse(visible.value, "The modal must unmount after the transfer completes")
            } finally {
                scene.close()
                recomposer.close()
            }
        }
    }
}
