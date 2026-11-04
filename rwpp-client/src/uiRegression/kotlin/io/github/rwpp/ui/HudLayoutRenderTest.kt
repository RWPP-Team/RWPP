@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.hud.*
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudLayoutRenderTest : DesktopComposeGraphicsTest() {
    @Test
    fun runtimeConsoleEdgeZoneMovesOnMouseAndLeavesConsoleInputUntouched() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(1280, 720))
        val surface = Surface.makeRasterN32Premul(1280, 720)
        val console = HudComponentState(
            HudComponentData("hud_console_vertical", 0f, 0f, relativeX = 1f, relativeY = 0f)
        )
        var saveCount = 0
        var time = 0L
        fun render() {
            while (true) (tasks.poll() ?: break).run()
            time += 16_666_667L
            recomposer.performFrame(time)
            while (true) (tasks.poll() ?: break).run()
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }

        try {
            scene.setContent {
                MaterialTheme {
                    HudViewport {
                        HudEditor(
                            components = listOf(console),
                            isEditMode = false,
                            onEditModeChange = {},
                            onAddComponent = {},
                            onDeleteComponent = {},
                            availableTemplates = mapOf(
                                "hud_console_vertical" to HudComponentTemplate(
                                    "Command console", 240f, 720f, fillHeight = true
                                ) { Box(Modifier.fillMaxSize()) }
                            ),
                            onSaveConfig = { saveCount++ }
                        )
                    }
                }
            }
            repeat(5) { render() }
            assertEquals(240f, console.width, 1f)
            assertEquals(720f, console.height, 1f)

            val original = Offset(console.x, console.y)
            val moveZone = console.moveZoneBounds(1280f).center
            val touchPress = scene.sendPointerEvent(
                PointerEventType.Press, moveZone, type = PointerType.Touch
            )
            assertFalse(touchPress.anyChangeConsumed, "Touch on the edge zone must remain available to the game")
            val touchMove = scene.sendPointerEvent(
                PointerEventType.Move, moveZone + Offset(-72f, 48f), type = PointerType.Touch
            )
            assertFalse(touchMove.anyChangeConsumed, "Touch movement outside the edge zone must not be captured")
            val touchRelease = scene.sendPointerEvent(
                PointerEventType.Release, moveZone + Offset(-72f, 48f), type = PointerType.Touch
            )
            assertFalse(touchRelease.anyChangeConsumed)
            assertEquals(0, saveCount, "Touching or dragging the edge zone must not save HUD coordinates")
            assertEquals(original.x, console.x)
            assertEquals(original.y, console.y)

            val consolePosition = Offset(console.x + 120f, console.y + 100f)
            val consolePress = scene.sendPointerEvent(
                PointerEventType.Press, consolePosition, button = PointerButton.Primary, type = PointerType.Mouse
            )
            assertFalse(consolePress.anyChangeConsumed, "The edge drag zone must not cover minimap or command controls")
            scene.sendPointerEvent(
                PointerEventType.Release, consolePosition, button = PointerButton.Primary, type = PointerType.Mouse
            )

            val mousePress = scene.sendPointerEvent(
                PointerEventType.Press, moveZone, button = PointerButton.Primary, type = PointerType.Mouse
            )
            assertTrue(mousePress.anyChangeConsumed, "Mouse press on the edge zone should start the drag")
            assertEquals(0, saveCount, "Saving waits until a real drag completes")
            val mousePosition = moveZone + Offset(-72f, 48f)
            assertTrue(
                scene.sendPointerEvent(
                    PointerEventType.Move,
                    mousePosition,
                    type = PointerType.Mouse
                ).anyChangeConsumed
            )
            assertTrue(
                scene.sendPointerEvent(
                    PointerEventType.Release, mousePosition, button = PointerButton.Primary, type = PointerType.Mouse
                ).anyChangeConsumed
            )
            assertTrue(console.x < original.x || console.y > original.y, "Mouse drag should move the console")
            assertEquals(1, saveCount, "A completed mouse drag persists HUD coordinates once")
            assertEquals(console.x, console.data.xDp, 1f)
            assertEquals(console.y, console.data.yDp, 1f)
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    @Test
    fun fullAxisAndRelativeAnchorsAreMeasuredAtMultipleWindowSizes() {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(1280, 720))
        val surface = Surface.makeRasterN32Premul(1920, 1080)
        val horizontal = HudComponentState(HudComponentData("horizontal", 0f, 0f, relativeX = 0f, relativeY = 1f))
        val vertical = HudComponentState(HudComponentData("vertical", 0f, 0f, relativeX = 1f, relativeY = 0f))
        val corner = HudComponentState(HudComponentData("corner", 0f, 0f, relativeX = 1f, relativeY = 1f))
        val compact = HudComponentState(HudComponentData("compact", 0f, 0f, relativeX = 0f, relativeY = 0f))
        val bounds = mutableMapOf<String, Rect>()
        var time = 0L
        try {
            scene.setContent {
                MaterialTheme {
                    HudViewport {
                        HudEditor(
                            listOf(horizontal, vertical, corner, compact), false, {}, {}, {}, mapOf(
                                "compact" to HudComponentTemplate(
                                    "compact",
                                    235f,
                                    38f,
                                    compactHeight = true,
                                    compactWidth = true
                                ) {
                                    Box(
                                        Modifier.size(72.dp, 18.dp)
                                            .onGloballyPositioned { bounds["compact"] = it.boundsInRoot() })
                                },
                                "horizontal" to HudComponentTemplate("horizontal", 640f, 190f, fillWidth = true) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.DarkGray)
                                            .onGloballyPositioned { bounds["horizontal"] = it.boundsInRoot() })
                                },
                                "vertical" to HudComponentTemplate("vertical", 260f, 420f, fillHeight = true) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.Gray)
                                            .onGloballyPositioned { bounds["vertical"] = it.boundsInRoot() })
                                },
                                "corner" to HudComponentTemplate("corner", 200f, 200f) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.Cyan)
                                            .onGloballyPositioned { bounds["corner"] = it.boundsInRoot() })
                                }
                            ))
                    }
                }
            }
            listOf(IntSize(1280, 720), IntSize(640, 360), IntSize(1920, 1080), IntSize(1280, 900)).forEach { size ->
                scene.size = size
                repeat(5) {
                    while (true) (tasks.poll() ?: break).run()
                    time += 16_666_667L
                    recomposer.performFrame(time)
                    scene.measureAndLayout()
                    scene.draw(surface.canvas.asComposeCanvas())
                }
                val tight = bounds.getValue("compact")
                assertEquals(72f, compact.width, 1f, "Compact width follows content")
                assertEquals(18f, compact.height, 1f, "Compact height follows content")
                val bar = bounds.getValue("horizontal")
                val side = bounds.getValue("vertical")
                val mini = bounds.getValue("corner")
                assertEquals(size.width.toFloat(), bar.width, 1f, "Horizontal HUD fills $size")
                assertEquals(size.height.toFloat(), side.height, 1f, "Vertical HUD fills $size")
                assertEquals(size.width.toFloat(), mini.right, 1f, "Right anchor at $size")
                assertEquals(size.height.toFloat(), mini.bottom, 1f, "Bottom anchor at $size")
                assertEquals(200f * hudViewportScale(size.width.toFloat(), size.height.toFloat()), mini.width, 1f)
            }
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }
}
