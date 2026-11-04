@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.*
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroChatInputTest : DesktopComposeGraphicsTest() {
    @Test
    fun hudChatMetadataIsOptionalAndNameUsesNativeColor() {
        val plain = hudChatLine("玩家", "你好", 0xffff0000.toInt(), "B", "12:34", false, false)
        assertEquals("玩家: 你好", plain.text)
        assertEquals(androidx.compose.ui.graphics.Color.Red, plain.spanStyles.single().item.color)
        assertEquals("[12:34] [B] 玩家: 你好", hudChatLine("玩家", "你好", -1, "B", "12:34", true, true).text)
    }

    @Test
    fun aeroChatFocusesInputSendsOnEnterAndConsumesEscape() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val component = object : PlatformComponent {
            override val locationOnScreen = java.awt.Point(0, 0)
            override val density = androidx.compose.ui.unit.Density(1f)
            override fun enableInput(inputMethodRequests: java.awt.im.InputMethodRequests) {}
            override fun disableInput() {}
            override fun endComposition() {}
        }
        val inputService = DesktopTextInputService2(component)
        var activeTextInputRequest: PlatformTextInputMethodRequest? = null
        val platform = object : PlatformContext by PlatformContext.Empty() {
            override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
                activeTextInputRequest = request
                inputService.startInputMethod(request)
            }
        }
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(1024, 720), platformContext = platform)
        val surface = Surface.makeRasterN32Premul(1024, 720)
        val sent = mutableListOf<Pair<String, Boolean>>()
        var closed = 0
        var time = 0L
        fun frames() = repeat(6) {
            dispatcher.drain()
            time += 16_666_667L
            recomposer.performFrame(time)
            dispatcher.drain()
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }
        try {
            scene.setContent {
                MaterialTheme {
                    AeroChatPane(
                        AnnotatedString("history"), true, {},
                        { text, team -> sent.add(text to team) }, { closed++ }, translate = { key ->
                            mapOf(
                                "rwpp.ingame.teamChat" to "Team chat", "rwpp.ingame.chat" to "Chat",
                                "rwpp.ingame.messagePlaceholder" to "Message", "rwpp.ingame.teamOnly" to "Team only",
                                "rwpp.common.send" to "Send", "rwpp.common.close" to "Close"
                            ).getValue(key)
                        })
                }
            }
            frames()
            scene.sendKeyEvent(desktopComposeKeyEvent(Key.H, KeyEventType.KeyDown, 'h'))
            scene.sendKeyEvent(desktopComposeKeyEvent(Key.I, KeyEventType.KeyDown, 'i'))
            frames()
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
            assertEquals(listOf("hi" to true), sent)
            scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
            assertEquals(1, sent.size)

            // The desktop offscreen bridge commits printable AWT key presses through the active
            // platform request, while composition itself arrives through InputMethodEvent.
            activeTextInputRequest!!.editText { commitText("x", 1) }
            frames()
            fun ime(text: String, committed: Int) = java.awt.event.InputMethodEvent(
                java.awt.Canvas(),
                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, java.text.AttributedString(text).iterator,
                committed, java.awt.font.TextHitInfo.leading(text.length - committed), null
            )
            inputService.inputMethodTextChanged(ime("zhongwen", 0))
            frames()
            inputService.inputMethodTextChanged(ime("中文", 2))
            frames()
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
            assertEquals("hix中文" to true, sent.last())
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
            assertEquals(1, closed)
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }
}
