@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import io.github.rwpp.game.room.scheduleRoomAiAddition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Surface
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class RoomAiAdditionRegressionTest : DesktopComposeGraphicsTest() {
    @Test
    fun addRunsOnGameScopeAndRefreshRunsOnUiScopeAfterwards() {
        val gameDispatcher = QueuedDispatcher()
        val uiDispatcher = QueuedDispatcher()
        val gameScope = CoroutineScope(gameDispatcher + SupervisorJob())
        val uiScope = CoroutineScope(uiDispatcher + SupervisorJob())
        var addedCount: Int? = null
        var refreshed = false

        try {
            scheduleRoomAiAddition(
                addAI = { addedCount = it },
                count = 10,
                gameScope = gameScope,
                uiScope = uiScope,
                refresh = { refreshed = true },
            )

            assertNull(addedCount, "Native room changes must wait for the game dispatcher")
            assertFalse(refreshed, "Compose state must not refresh from the game dispatcher")

            gameDispatcher.drain()
            assertEquals(10, addedCount)
            assertFalse(refreshed, "UI refresh must be posted to the UI scope")

            uiDispatcher.drain()
            assertTrue(refreshed)
        } finally {
            gameScope.cancel()
            uiScope.cancel()
        }
    }

    @Test
    fun twoSameFrameRoomRefreshesCannotCancelRememberedPlayerRefresh() {
        val dispatcher = QueuedDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(320, 200))
        val surface = Surface.makeRasterN32Premul(320, 200)
        var booleanRefresh by mutableStateOf(false)
        var refreshRevision by mutableIntStateOf(0)
        var roomPlayers = listOf("Human")
        var booleanCachedPlayers = emptyList<String>()
        var revisionCachedPlayers = emptyList<String>()

        fun renderFrame(frame: Int) {
            dispatcher.drain()
            recomposer.performFrame(frame * 16_666_667L)
            dispatcher.drain()
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
        }

        try {
            scene.setContent {
                // The native room list is not Compose state; refresh is what invalidates this cache.
                val booleanCached = remember(booleanRefresh) { roomPlayers }
                val revisionCached = remember(refreshRevision) { roomPlayers }
                SideEffect {
                    booleanCachedPlayers = booleanCached
                    revisionCachedPlayers = revisionCached
                }
                Column {
                    BasicText(booleanCached.joinToString())
                    BasicText(revisionCached.joinToString())
                }
            }
            renderFrame(1)
            assertEquals(listOf("Human"), booleanCachedPlayers)
            assertEquals(listOf("Human"), revisionCachedPlayers)

            roomPlayers = listOf("Human", "AI")
            repeat(2) { booleanRefresh = !booleanRefresh }
            repeat(2) { refreshRevision++ }
            renderFrame(2)

            assertEquals(
                listOf("Human"), booleanCachedPlayers,
                "Two Boolean toggles coalesce back to the prior remember key"
            )
            assertEquals(
                listOf("Human", "AI"), revisionCachedPlayers,
                "A monotonic refresh key must expose the newly registered native team"
            )
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
