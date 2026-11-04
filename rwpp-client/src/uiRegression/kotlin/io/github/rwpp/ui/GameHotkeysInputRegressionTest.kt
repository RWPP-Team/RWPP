@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Surface
import kotlin.test.*

class GameHotkeysInputRegressionTest : DesktopComposeGraphicsTest() {
    @Test
    fun clickingHotkeyTextFieldCapturesCombinationAndLeavesCaptureMode() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(420, 120))
        val surface = Surface.makeRasterN32Premul(420, 120)
        var binding by mutableStateOf("CTRL+T")
        var focused = false
        var capturing = false
        var bounds by mutableStateOf<Rect?>(null)
        val savedBindings = mutableListOf<String>()
        var frameTime = 0L

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
                MaterialTheme {
                    HotkeyBindingField(
                        binding = binding,
                        prompt = "Press a key",
                        modifier = Modifier.width(320.dp).onGloballyPositioned { bounds = it.boundsInRoot() },
                        onBindingChanged = { value ->
                            savedBindings += value
                            binding = value
                        },
                        onCaptureChanged = {
                            focused = it
                            capturing = it
                        },
                    )
                }
            }
            renderFrame()

            val fieldBounds = assertNotNull(bounds, "the hotkey field should be laid out for pointer input")
            val tap = fieldBounds.center
            scene.sendPointerEvent(PointerEventType.Move, tap)
            scene.sendPointerEvent(PointerEventType.Press, tap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, tap, button = PointerButton.Primary)
            renderFrame()

            assertTrue(focused, "the clicked AeroTextField must gain focus and enter key-capture mode")
            assertTrue(capturing)
            assertTrue(
                scene.sendKeyEvent(
                    desktopComposeKeyEvent(Key.T, KeyEventType.KeyDown, 'T', ctrl = true, shift = true)
                ), "the focused field must consume the Ctrl+Shift+T key event"
            )
            renderFrame()

            assertEquals("CTRL+SHIFT+T", binding, "the shortcut must update through the focused TextField UI")
            assertEquals(listOf("CTRL+SHIFT+T"), savedBindings)
            assertFalse(capturing, "saving a shortcut must exit capture mode")
            assertFalse(focused, "saving a shortcut must clear TextField focus")
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }
}
