@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.game.hud.normalizeHudComponents
import io.github.rwpp.logic.hotkeys.hotkeyBindingSlots
import io.github.rwpp.logic.hotkeys.validHotkeyBindings
import io.github.rwpp.utils.hudTooltipPosition
import org.jetbrains.skia.Surface
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class RenderThreadRegressionTest : DesktopComposeGraphicsTest() {
    @Test
    fun hoverInformationStaysVisibleWhenTheConsoleMovesOrWindowResizes() {
        assertEquals(
            IntOffset(592, 100), hudTooltipPosition(
                IntRect(900, 100, 1000, 200),
                IntSize(1024, 720), IntSize(300, 200), 8
            )
        )
        assertEquals(
            IntOffset(108, 100), hudTooltipPosition(
                IntRect(0, 100, 100, 200),
                IntSize(1024, 720), IntSize(300, 200), 8
            )
        )
        assertEquals(
            IntOffset(0, 0), hudTooltipPosition(
                IntRect(0, 100, 100, 200),
                IntSize(200, 100), IntSize(300, 200), 8
            )
        )
    }

    @Test
    fun coldIconRequestsAreBudgetedWithoutDroppingWork() {
        val dispatcher = RenderThreadDispatcher()
        var rendered = 0
        repeat(20) { dispatcher.dispatch(EmptyCoroutineContext) { rendered++ } }
        dispatcher.drain(maxTasks = 4)
        assertEquals(4, rendered)
        dispatcher.drain()
        assertEquals(20, rendered)
    }

    @Test
    fun oneSlowIconDefersFurtherRequestsToTheNextFrame() {
        var time = 0L
        val dispatcher = RenderThreadDispatcher { time }
        val completed = ArrayList<Int>()
        repeat(4) { index ->
            dispatcher.dispatch(EmptyCoroutineContext) {
                completed += index
                if (index == 0) time += 3_000_000L
            }
        }
        dispatcher.drain(maxTasks = 4, maxNanos = 2_000_000L)
        assertEquals(listOf(0), completed)
        dispatcher.drain(maxTasks = 4, maxNanos = 2_000_000L)
        assertEquals(listOf(0, 1, 2, 3), completed)
    }

    @Test
    fun nativeAndAwtThreadsCannotExecuteSceneWorkInline() {
        val owner = Thread.currentThread()
        val dispatcher = RenderThreadDispatcher()
        val executed = CompletableFuture<Thread>()
        Thread { dispatcher.dispatch(EmptyCoroutineContext) { executed.complete(Thread.currentThread()) } }.apply { start(); join() }
        assertFalse(executed.isDone)
        dispatcher.drain()
        assertSame(owner, executed.get())
        val rejected = CompletableFuture<Boolean>()
        Thread { rejected.complete(runCatching { dispatcher.drain() }.isFailure) }.apply { start(); join() }
        assertTrue(rejected.get())
    }

    @Test
    fun massSelectionAndHoverLayersCanDetachRepeatedlyOnTheRenderOwner() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(1024, 720))
        val surface = Surface.makeRasterN32Premul(1024, 720)
        var selected by mutableIntStateOf(0)
        var hover by mutableStateOf(false)
        var attached = 0
        var detached = 0
        try {
            scene.setContent {
                MaterialTheme {
                    Column {
                        repeat(selected) { unit ->
                            key(unit) {
                                DisposableEffect(Unit) { attached++; onDispose { detached++ } }
                                BasicText("Unit $unit")
                            }
                        }
                        CommandTooltipLayer()
                    }
                }
            }
            repeat(150) { frame ->
                Thread {
                    dispatcher.dispatch(EmptyCoroutineContext) {
                        selected = if (frame % 3 == 0) 120 else if (frame % 3 == 1) 1 else 0
                        hover = frame % 4 == 0
                        CommandHover.current.value = if (hover) CommandTooltip(
                            androidx.compose.ui.geometry.Rect(600f, 100f, 700f, 200f),
                            "Unit",
                            "HP / shield"
                        ) else null
                    }
                }.apply { start(); join() }
                dispatcher.drain()
                recomposer.performFrame((frame + 1) * 16_666_667L)
                dispatcher.drain()
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
            assertTrue(attached >= 6000, "Exercise the RectManager removal path with thousands of nodes")
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
        assertEquals(attached, detached)
    }

    @Test
    fun legacyHudCombinesIntoOneVerticalConsoleAndKeepsOtherWidgets() {
        val old = HudComponentData("hud_console", 400f, 500f, relativeX = 0.5f, relativeY = 1f)
        val chat = HudComponentData("chat", 20f, 20f)
        val components = mutableListOf(old, HudComponentData("minimap", 5f, 5f), chat)
        normalizeHudComponents(components)
        assertEquals(listOf("hud_console_vertical", "chat"), components.map { it.id })
        assertEquals(1f, old.relativeX)
        assertEquals(0f, old.relativeY)
        assertSame(chat, components[1])
        normalizeHudComponents(components)
        assertEquals(2, components.size)
    }

    @Test
    fun hotkeySlotsHideNativeNullBindingsAndKeepTwoEditableAlternatives() {
        val keys = setOf("T", "SPACE")
        val slots = { labels: List<String> -> hotkeyBindingSlots(labels) { validHotkeyBindings(it, keys) } }
        assertEquals(listOf("T", "CTRL+T"), slots(listOf("<null>", "T", "CTRL+T")))
        assertEquals(listOf("", ""), slots(listOf("NONE", "CLEARED", "")))
        assertEquals(listOf("SPACE", ""), slots(listOf("SPACE")))
    }

    @Test
    fun chatIgnoresEmptyInputAndUsesNativeTeamPrefix() {
        assertNull(outgoingHudChat("  ", false))
        assertEquals("hello", outgoingHudChat(" hello ", false))
        assertEquals("-t 集合", outgoingHudChat(" 集合 ", true))
        com.corrodinggames.rts.gameFramework.gui.BattleInterface::class.java
            .getDeclaredMethod("selectMenuOption", Int::class.javaPrimitiveType)
    }
}
