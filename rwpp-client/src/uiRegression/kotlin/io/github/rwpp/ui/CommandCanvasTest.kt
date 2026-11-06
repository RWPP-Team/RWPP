@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.game.hud.HudActionRect
import io.github.rwpp.game.hud.HudActionSize
import io.github.rwpp.game.hud.nativeHudActionLayout
import io.github.rwpp.graphics.hudImageFromPixels
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class CommandCanvasTest : DesktopComposeGraphicsTest() {
    @Test
    fun selectedUnitCanvasPassesBlankPlanningGesturesButKeepsActionButtons() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(260, 360))
        val surface = Surface.makeRasterN32Premul(260, 360)
        val model = mutableStateOf(CommandFrame("Builder", buttons = listOf(CommandButton("Build", clickSound = null))))
        try {
            scene.setContent { CommandSurface(model, modifier = Modifier.fillMaxSize(), plannerOwnsBlank = { true }) }
            repeat(4) { frame ->
                dispatcher.drain(); recomposer.performFrame((frame + 1) * 16_666_667L)
                dispatcher.drain(); scene.measureAndLayout(); scene.draw(surface.canvas.asComposeCanvas())
            }
            assertFalse(
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(20f, 260f),
                    button = PointerButton.Primary
                ).anyChangeConsumed
            )
            assertFalse(scene.sendPointerEvent(PointerEventType.Move, Offset(120f, 300f)).anyChangeConsumed)
            assertFalse(
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    Offset(120f, 300f),
                    button = PointerButton.Primary
                ).anyChangeConsumed
            )
            assertTrue(
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(20f, 20f),
                    button = PointerButton.Primary
                ).anyChangeConsumed
            )
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 20f), button = PointerButton.Primary)
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    @Test
    fun shortActionRowsKeepIconsAndProductionVisibleBehindCaptions() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(260, 160))
        val surface = Surface.makeRasterN32Premul(260, 160)
        val icon = hudImageFromPixels(IntArray(24 * 24) { 0xffee3388.toInt() }, 24, 24)
        val model = mutableStateOf(
            CommandFrame(
                buttons = listOf(
                    CommandButton("T\n$100", heightScale = 0.4f, image = icon, progress = 0.5f)
                )
            )
        )
        try {
            scene.setContent { CommandSurface(model, modifier = Modifier.fillMaxSize()) }
            repeat(4) { frame ->
                dispatcher.drain(); recomposer.performFrame((frame + 1) * 16_666_667L)
                dispatcher.drain(); scene.measureAndLayout()
                surface.canvas.clear(0); scene.draw(surface.canvas.asComposeCanvas())
            }
            val pixels = IntArray(260 * 160)
            surface.makeImageSnapshot().use { snapshot ->
                org.jetbrains.skia.Bitmap().use { bitmap ->
                    bitmap.allocPixels(snapshot.imageInfo); assertTrue(snapshot.readPixels(bitmap))
                    for (y in 0 until 160) for (x in 0 until 260) pixels[y * 260 + x] = bitmap.getColor(x, y)
                }
            }
            // A 0.4-height native row is only 25 px high. The previous 30 px opaque caption
            // painted over the icon entirely; it must now remain visible in this short row.
            val colored = (60..77).sumOf { y ->
                (54..71).count { x ->
                    val p = pixels[y * 260 + x]
                    (p ushr 16 and 255) > 150 && (p and 255) > 70
                }
            }
            assertTrue(colored > 150, "Short rows must retain a substantial visible sprite; pixels=$colored")
            assertNotEquals(
                pixels[63 * 260 + 63], pixels[74 * 260 + 63],
                "Production fill must remain visible over the lower half of the icon"
            )
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    @Test
    fun productionKeepsInformationAcrossRepeatedClicksAndDismissesInsideConsoleGaps() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(260, 360))
        val surface = Surface.makeRasterN32Premul(260, 360)
        val selection = HudProductionSelection()
        var builds = 0
        var time = 0L
        fun button(id: String) = CommandButton(
            id, identity = id, clickSound = null,
            info = { id to "Production details" }, activate = {
                if (selection.tap(id)) builds++
                HudQueuePopupTouch.active = true
                HudQueuePopupTouch.selectedIdentity = id
                HudQueuePopupTouch.onDismiss = selection::clear
            })

        val model = mutableStateOf(
            CommandFrame(
                "Factory", buttons = listOf(button("Tank"), button("Builder")),
                info = { "Factory" to "Unit statistics" })
        )

        fun render() {
            dispatcher.drain(); time += 16_666_667L; recomposer.performFrame(time)
            dispatcher.drain(); scene.measureAndLayout(); surface.canvas.clear(0)
            scene.draw(surface.canvas.asComposeCanvas())
        }

        fun press(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, point, button = PointerButton.Primary)
        }

        fun release(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
        }

        fun tap(point: Offset) {
            press(point); release(point)
        }
        try {
            scene.setContent { CommandSurface(model, modifier = Modifier.fillMaxSize()) }
            repeat(4) { render() }
            tap(Offset(20f, 20f))
            assertEquals(0, builds)
            assertEquals("Tank", CommandHover.current.value?.title)
            repeat(3) {
                time += 2_000_000_000L; render() // No double-tap timing window.
                press(Offset(20f, 20f))
                assertEquals(
                    "Tank",
                    CommandHover.current.value?.title,
                    "Pressing the selected production must keep upper information"
                )
                release(Offset(20f, 20f))
            }
            assertEquals(3, builds)
            tap(Offset(160f, 20f))
            assertEquals(3, builds, "A different type is selected without building immediately")
            assertEquals("Builder", selection.identity)
            tap(Offset(20f, 70f)) // Row gap: real blank canvas area inside the console.
            assertFalse(HudQueuePopupTouch.active)
            assertNull(selection.identity)
            tap(Offset(160f, 20f))
            assertEquals(3, builds, "Dismissal resets selection synchronously before the next click")
            tap(Offset(160f, 20f))
            assertEquals(4, builds)
            tap(Offset(20f, 2f)) // Header strip above the action rows.
            assertNull(selection.identity)
            assertEquals(
                "Factory",
                CommandHover.current.value?.title,
                "Console header restores native unit information"
            )
        } finally {
            HudQueuePopupTouch.active = false; HudQueuePopupTouch.onDismiss = null
            HudQueuePopupTouch.selectedIdentity = null; HudQueuePopupTouch.popup =
                androidx.compose.ui.geometry.Rect.Zero
            scene.close(); recomposer.close(); surface.close(); CommandHover.current.value = null
        }
    }

    @Test
    fun selectionResizeHoverAndMenuClicksUseOneStableCanvas() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(260, 720))
        val surface = Surface.makeRasterN32Premul(1024, 900)
        val model = mutableStateOf(CommandFrame())
        val incoming = AtomicReference(CommandFrame())
        val owner = Thread.currentThread()
        val frameRegistration =
            registerHudFrame(Runnable { assertSame(owner, Thread.currentThread()); model.value = incoming.get() })
        var attached = 0;
        var detached = 0;
        var draws = 0;
        var clicked = 0
        var time = 0L
        fun render() {
            HudFrameClock.tick(); dispatcher.drain(); time += 16_666_667L
            recomposer.performFrame(time); dispatcher.drain(); scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }
        try {
            scene.setContent {
                DisposableEffect(Unit) { attached++; onDispose { detached++ } }
                Box {
                    CommandSurface(model, modifier = Modifier.fillMaxSize().drawWithContent { draws++; drawContent() })
                    CommandTooltipLayer()
                }
            }
            repeat(180) { frame ->
                Thread {
                    incoming.set(
                        CommandFrame(
                            "Selection $frame", 0.5f, 0.2f, frame,
                            List(if (frame % 3 == 0) 2000 else if (frame % 3 == 1) 1 else 0) {
                                CommandButton(
                                    "Action $it",
                                    queued = 3
                                )
                            })
                    )
                }.apply { start(); join() }
                if (frame % 30 == 0) scene.size = if (frame % 60 == 0) IntSize(260, 720) else IntSize(320, 480)
                CommandHover.current.value = if (frame % 4 == 0) CommandTooltip(
                    androidx.compose.ui.geometry.Rect(200f, 40f, 260f, 120f),
                    "Unit",
                    "Description"
                ) else null
                render()
            }
            assertEquals(1, attached); assertEquals(0, detached); assertTrue(draws >= 180)
            incoming.set(
                CommandFrame(
                    "Factory",
                    1f,
                    count = 1,
                    buttons = listOf(CommandButton("Build", activate = { clicked += if (it) -1 else 1 }))
                )
            )
            repeat(3) { render() }
            CommandHover.current.value = null
            scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 20f))
            assertEquals(
                "Build",
                CommandHover.current.value?.title,
                "Hovering an action must open the information panel without a click"
            )
            scene.sendPointerEvent(PointerEventType.Move, Offset(240f, 400f))
            assertEquals(
                "Factory",
                CommandHover.current.value?.title,
                "Hovering the console must show the selected unit information"
            )
            scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 20f))
            assertTrue(
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    Offset(20f, 20f),
                    button = PointerButton.Primary
                ).anyChangeConsumed
            )
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 20f), button = PointerButton.Primary)
            assertEquals(1, clicked)
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 20f), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 20f), button = PointerButton.Secondary)
            assertEquals(0, clicked, "Secondary button reaches the remove/cancel callback")
            incoming.set(
                CommandFrame(
                    "Unit",
                    count = 1,
                    buttons = listOf(CommandButton("Unit type", activate = { clicked++ }, showInfo = false))
                )
            )
            repeat(3) { render() }
            CommandHover.current.value = null
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 20f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 20f), button = PointerButton.Primary)
            assertEquals(1, clicked)
            assertNull(CommandHover.current.value, "Unit type selection must not show action information")

            val icon = hudImageFromPixels(IntArray(24 * 24) { p ->
                if (p % 24 in 6..17 && p / 24 in 3..20) 0xff54cfc8.toInt() else 0
            }, 24, 24)
            incoming.set(CommandFrame("建造中心", 0.85f, 0.4f, 3, List(24) {
                CommandButton(
                    "单位 $it\n$500", queued = if (it == 0) 12 else 0,
                    progress = if (it == 0) 0.4f else 0f, image = icon
                )
            }))
            repeat(3) { render() }
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/ui-regression"))
            surface.makeImageSnapshot().use { image ->
                image.encodeToData()!!.use {
                    java.nio.file.Files.write(
                        java.nio.file.Path.of("build/ui-regression/aero-command-console.png"),
                        it.bytes
                    )
                }
            }
            var selected = -1
            incoming.set(CommandFrame(buttons = List(80) { index ->
                CommandButton(
                    "Action $index",
                    activate = { selected = index })
            }))
            repeat(3) { render() }
            scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 40f))
            assertTrue(
                scene.sendPointerEvent(
                    PointerEventType.Scroll,
                    Offset(20f, 40f),
                    scrollDelta = Offset(0f, 5f)
                ).anyChangeConsumed
            )
            repeat(3) { render() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 40f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 40f), button = PointerButton.Primary)
            assertTrue(selected >= 4, "Wheel must reach later actions; got $selected")
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(20f, 40f), scrollDelta = Offset(0f, -500f))
            repeat(3) { render() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 40f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 40f), button = PointerButton.Primary)
            assertEquals(0, selected, "Wheel must return to the first action")
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 260f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(20f, 40f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 40f), button = PointerButton.Primary)
            repeat(3) { render() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 40f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 40f), button = PointerButton.Primary)
            assertTrue(selected >= 4, "Dragging upward must reveal later actions; got $selected")

        } finally {
            frameRegistration.close(); CommandHover.current.value = null
            scene.close(); recomposer.close(); surface.close()
        }
        assertEquals(1, detached)
    }

    @Test
    fun scrollingHitTestsClampAfterSwitchingFromALargeArmyToOneUnit() {
        val geometry = CommandGeometry().apply {
            top = 66f; height = 400f; contentHeight = 2000f
            rects = listOf(HudActionRect(0, 0, 120, 72))
        }
        geometry.moveScroll(1200f)
        assertNull(geometry.hit(Offset(20f, 90f)))
        geometry.contentHeight = 72f
        assertEquals(0f, geometry.offset)
        assertEquals(0, geometry.hit(Offset(20f, 90f)))
        assertNull(geometry.hit(Offset(20f, 20f)))
    }

    @Test
    fun scrollbarTrackJumpsAndMouseDragScrollsWithoutStealingProductionClicks() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(260, 356))
        val surface = Surface.makeRasterN32Premul(260, 356)
        var selected = -1
        var time = 0L
        val model = mutableStateOf(
            CommandFrame(buttons = List(20) { index ->
                CommandButton("Action $index", activate = { selected = index })
            })
        )

        fun render() {
            dispatcher.drain(); time += 16_666_667L
            recomposer.performFrame(time); dispatcher.drain(); scene.measureAndLayout()
            surface.canvas.clear(0); scene.draw(surface.canvas.asComposeCanvas())
        }

        fun press(point: Offset) = scene.sendPointerEvent(
            PointerEventType.Press, point, button = PointerButton.Primary
        )

        fun release(point: Offset) = scene.sendPointerEvent(
            PointerEventType.Release, point, button = PointerButton.Primary
        )
        try {
            scene.setContent { CommandSurface(model, modifier = Modifier.fillMaxSize()) }
            repeat(4) { render() }

            press(Offset(180f, 40f)); release(Offset(180f, 40f))
            assertEquals(1, selected, "Right-column production remains clickable beside the reserved gutter")

            press(Offset(254f, 240f)); release(Offset(254f, 240f))
            assertEquals(1, selected, "Track interaction must not activate a production action")
            render()
            press(Offset(254f, 180f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(254f, 350f))
            release(Offset(254f, 350f))
            render()

            press(Offset(20f, 110f)); release(Offset(20f, 110f))
            assertTrue(selected >= 12, "Dragging the thumb to the bottom should expose later actions; got $selected")
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    @Test
    fun scrollbarTrackJumpsAndThumbDragsWithoutCoveringActionHitboxes() {
        val geometry = CommandGeometry().apply {
            top = 56f; height = 356f; width = 260f; contentHeight = 1200f
            scrollbarHitWidth = 12f
            rects = nativeHudActionLayout(248, 64, 2, List(20) { HudActionSize(1f, -1) })
        }
        val minimumThumb = 18f
        val initial = geometry.scrollbar(minimumThumb)!!
        assertEquals(56f, initial.thumbTop)

        val actionPoint = Offset(180f, 90f)
        assertEquals(1, geometry.hit(actionPoint))
        assertNull(geometry.scrollbarGrabOffset(actionPoint, minimumThumb), "Production buttons keep their hit area")

        val trackPoint = Offset(254f, 240f)
        val trackGrabOffset = geometry.scrollbarGrabOffset(trackPoint, minimumThumb)
        assertNotNull(trackGrabOffset, "The reserved right gutter is an interactive scrollbar track")
        geometry.dragScrollbar(trackPoint.y, trackGrabOffset, minimumThumb)
        assertTrue(geometry.offset > 0f, "Clicking the track should jump to the requested position")

        val current = geometry.scrollbar(minimumThumb)!!
        val thumbGrabOffset = geometry.scrollbarGrabOffset(
            Offset(254f, current.thumbTop + 5f), minimumThumb
        )
        assertEquals(5f, thumbGrabOffset)
        geometry.dragScrollbar(geometry.height, thumbGrabOffset!!, minimumThumb)
        assertEquals(current.maxOffset, geometry.offset)
        geometry.dragScrollbar(geometry.top, thumbGrabOffset, minimumThumb)
        assertEquals(0f, geometry.offset)
    }

    @Test
    fun iconRequestsFollowOnlyTheScrolledVisibleRows() {
        val geometry = CommandGeometry().apply {
            top = 56f; height = 196f
            frame = CommandFrame(buttons = List(1000) { CommandButton("Action $it", identity = "id-$it") })
            rects = nativeHudActionLayout(240, 64, 2, List(1000) { HudActionSize(1f, -1) })
            contentHeight = (rects.last().y + rects.last().height).toFloat()
        }
        assertEquals((0..5).map { "id-$it" }.toSet(), geometry.visibleIds())
        geometry.moveScroll(68f * 200)
        assertEquals((400..405).map { "id-$it" }.toSet(), geometry.visibleIds())
    }
}
