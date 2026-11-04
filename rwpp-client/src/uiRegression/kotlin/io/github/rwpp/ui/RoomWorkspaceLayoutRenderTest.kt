@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroHeader
import io.github.rwpp.widget.v2.AeroPanel
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * Renders the production room map/chat panes through the production responsive workspace.
 * Player rows are fake game data, but use a real controlled LazyListState.
 */
class RoomWorkspaceLayoutRenderTest : DesktopComposeGraphicsTest() {
    private data class Viewport(val width: Int, val height: Int, val density: Float = 1f) {
        val logicalWidth get() = width / density
        val logicalHeight get() = height / density
        val compact get() = logicalWidth < 700f || logicalHeight < 500f
        val label get() = "${width}x${height}-d${density.toString().replace('.', 'p')}"
    }

    private class RenderedRoom(val viewport: Viewport) : AutoCloseable {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(
            recomposer,
            size = IntSize(viewport.width, viewport.height),
            density = Density(viewport.density),
        )
        val surface = Surface.makeRasterN32Premul(viewport.width, viewport.height)
        val bounds = mutableMapOf<String, Bounds>()
        val playerRows = mutableMapOf<Int, Bounds>()
        val pageScrollState = ScrollState(0)
        val playerListState = LazyListState()
        val chatHistoryScrollState = ScrollState(0)
        var frameTimeNanos = 0L
        var mapClicks = 0
        var optionsClicks = 0
        var startClicks = 0
        var exitClicks = 0
        val chatFocus = androidx.compose.ui.focus.FocusRequester()
        var chatFocused = false
        var draft by mutableStateOf("")
        var extraHistory by mutableStateOf("")

        fun setContent() {
            val compact = viewport.compact
            val history = AnnotatedString((0 until HISTORY_LINES).joinToString("\n") { index ->
                "[10:${(index % 60).toString().padStart(2, '0')}] OP${index.toString().padStart(2, '0')}: Ready"
            })

            scene.setContent {
                MaterialTheme {
                    RoomWorkspaceLayout(
                        modifier = Modifier.fillMaxSize(),
                        compact = compact,
                        pageScrollState = pageScrollState,
                        header = {
                            Row(
                                Modifier.fillMaxWidth().height(if (compact) 40.dp else 48.dp)
                                    .probe("header", bounds),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (compact) Arrangement.End else Arrangement.spacedBy(8.dp),
                            ) {
                                if (!compact) {
                                    AeroHeader(
                                        title = "MULTIPLAYER ROOM",
                                        tag = "NET // PROTOCOL V 4",
                                        modifier = Modifier.weight(1f).probe("decorative-header", bounds),
                                    )
                                }
                                AeroButton(
                                    text = "EXIT",
                                    onClick = { exitClicks++ },
                                    modifier = Modifier.width(80.dp).height(36.dp).probe("exit", bounds),
                                )
                            }
                        },
                        map = {
                            RoomMapPane(
                                mapPreview = {
                                    Box(
                                        Modifier.fillMaxSize().background(Color(0xFF243746)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("MAP PREVIEW", color = Color.White, fontSize = 11.sp)
                                    }
                                },
                                mapType = "Skirmish",
                                mapName = "Regression Map",
                                details = "Players: 4\nSize: 128 x 128\nFog: Enabled",
                                isHost = true,
                                onSelectMap = { mapClicks++ },
                                onOptions = { optionsClicks++ },
                                onStart = { startClicks++ },
                                sectorInfoLabel = "SECTOR INFO",
                                hostAccessLabel = "HOST ACCESS",
                                guestLabel = "GUEST",
                                startLabel = "Start",
                                compact = compact,
                                selectMapLabel = "MAP PICK",
                                optionsLabel = "SETTINGS",
                                modifier = Modifier.probe("map", bounds),
                                actionsModifier = Modifier.probe("map-actions", bounds),
                            )
                        },
                        players = {
                            AeroPanel(Modifier.fillMaxSize().probe("players", bounds)) {
                                Column(Modifier.fillMaxSize().padding(6.dp)) {
                                    if (!compact) {
                                        AeroHeader(
                                            "OPERATOR ROSTER", tag = "$PLAYER_COUNT PLAYERS",
                                            modifier = Modifier.probe("roster-header", bounds)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth().height(30.dp).probe("roster-columns", bounds),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("OPERATOR", Modifier.weight(0.55f), fontSize = 9.sp)
                                            Text("SPAWN", Modifier.weight(0.15f), fontSize = 9.sp)
                                            Text("TEAM", Modifier.weight(0.15f), fontSize = 9.sp)
                                            Text("PING", Modifier.weight(0.15f), fontSize = 9.sp)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        state = playerListState,
                                    ) {
                                        items((0 until PLAYER_COUNT).toList(), key = { it }) { index ->
                                            Row(
                                                Modifier.fillMaxWidth().height(38.dp)
                                                    .probeRow(index, playerRows),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.padding(horizontal = 5.dp)) {
                                                    Text("Operator $index", style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        "TEAM ${index % 4} • SPAWN $index • 42 ms",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        chat = {
                            RoomChatPanel(
                                modifier = Modifier.fillMaxWidth().probe("chat", bounds),
                                history = history + AnnotatedString(extraHistory),
                                message = draft,
                                onMessageChange = { draft = it },
                                onComposerFocusChanged = { chatFocused = it },
                                onSend = {},
                                chatFocusRequester = chatFocus,
                                isHost = true,
                                isSandbox = false,
                                isLocked = false,
                                onChangeSite = {},
                                onAddAI = {},
                                onToggleLock = {},
                                chatTitle = "TACTICAL COMMS",
                                chatTag = "CHANNELS // BROADCAST",
                                changeSiteDescription = "Change site",
                                teamLockDescription = "Lock room",
                                sendMessageDescription = "Send message",
                                addAiLabel = "Add AI",
                                compact = compact,
                                historyScrollState = chatHistoryScrollState,
                                toolbarModifier = Modifier.probe("chat-toolbar", bounds),
                                historyModifier = Modifier.probe("chat-history", bounds),
                                composerModifier = Modifier.probe("chat-input", bounds),
                            )
                        },
                    )
                }
            }
        }

        fun render(frames: Int = 5) {
            repeat(frames) {
                dispatcher.drain()
                frameTimeNanos += 16_666_667L
                recomposer.performFrame(frameTimeNanos)
                dispatcher.drain()
                scene.measureAndLayout()
                surface.canvas.clear(0xFF101722.toInt())
                scene.draw(surface.canvas.asComposeCanvas())
            }
        }

        fun savePng(path: Path) {
            Files.createDirectories(path.parent)
            surface.makeImageSnapshot().use { image ->
                image.encodeToData()!!.use { Files.write(path, it.bytes) }
            }
            assertTrue(Files.size(path) > 1_000L, "$path contains an actual Skia-rendered room page")
        }

        override fun close() {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    @Test
    fun compactRoomUsesWholePageScrollAndNaturallySizedChatHistory() {
        listOf(Viewport(320, 568), Viewport(640, 360, density = 1.5f)).forEach { viewport ->
            assertTrue(viewport.compact, "$viewport should use the Small workspace")
            val room = RenderedRoom(viewport)
            try {
                room.setContent()
                room.render()

                val top = room.snapshotBounds()
                val naturalHistory = top.getValue("chat-history")
                assertTrue(
                    room.pageScrollState.maxValue > 0,
                    "compact header, map/player row, chat history and composer must share a scrollable page at $viewport"
                )
                assertScrollStateIsUnattached(
                    room.chatHistoryScrollState,
                    "compact history has no nested scroll modifier at $viewport",
                )
                assertTrue(
                    naturalHistory.height / viewport.density >= HISTORY_LINES * 14f,
                    "all $HISTORY_LINES history lines retain their natural height at $viewport: $naturalHistory"
                )
                assertFalse(
                    "decorative-header" in top,
                    "compact header hides the decorative title/tag at $viewport"
                )
                assertFalse(
                    "roster-header" in top,
                    "compact player pane hides its decorative roster heading at $viewport"
                )
                assertFalse(
                    "roster-columns" in top,
                    "compact player pane hides desktop table columns at $viewport"
                )
                assertTrue(
                    top.getValue("exit").top >= 0f && top.getValue("exit").bottom <= viewport.height,
                    "Exit remains visible at the top of the compact page at $viewport"
                )
                assertMapLeftOfPlayers(top, viewport)
                assertTrue(
                    naturalHistory.height > top.getValue("chat").height * 0.8f,
                    "compact chat grows with its history instead of constraining it at $viewport"
                )
                assertTrue(
                    top.getValue("map-actions").height > 0f,
                    "actual compact map action controls are composed at $viewport"
                )

                val prefix = Path.of("build/ui-regression/room-workspace-${viewport.label}")
                room.savePng(Path.of("${prefix}-top.png"))

                val beforePageScroll = top
                val pageDelta = minOf(120f, room.pageScrollState.maxValue.toFloat())
                assertTrue(
                    room.pageScrollState.dispatchRawDelta(pageDelta) > 0f,
                    "the compact page consumes a vertical scroll at $viewport"
                )
                room.render(3)
                val afterPageScroll = room.snapshotBounds()
                val mapMoved = beforePageScroll.getValue("map").top - afterPageScroll.getValue("map").top
                val playersMoved = beforePageScroll.getValue("players").top - afterPageScroll.getValue("players").top
                val chatMoved = beforePageScroll.getValue("chat").top - afterPageScroll.getValue("chat").top
                assertTrue(
                    mapMoved > 20f && playersMoved > 20f && chatMoved > 20f,
                    "page scroll moves map, players and chat together at $viewport: map=$mapMoved players=$playersMoved chat=$chatMoved"
                )
                assertTrue(
                    abs(mapMoved - playersMoved) < 2f && abs(mapMoved - chatMoved) < 2f,
                    "the whole compact page moves as one scrollable surface at $viewport"
                )

                val rewind = -room.pageScrollState.value.toFloat()
                if (rewind != 0f) room.pageScrollState.dispatchRawDelta(rewind)
                room.render(3)
                val beforePlayerScroll = room.snapshotBounds()
                val pageValueBeforePlayers = room.pageScrollState.value
                val rowsBefore = room.playerRows.toMap()
                room.playerRows.clear()
                assertTrue(
                    room.playerListState.dispatchRawDelta(120f) > 0f,
                    "player rows have their own bounded scroll at $viewport"
                )
                room.render(3)
                val rowsAfter = room.playerRows.toMap()
                assertEquals(
                    pageValueBeforePlayers, room.pageScrollState.value,
                    "scrolling the player list must not move the page at $viewport"
                )
                assertSamePosition(beforePlayerScroll, room.snapshotBounds(), "map", viewport)
                assertSamePosition(beforePlayerScroll, room.snapshotBounds(), "chat", viewport)
                assertTrue(rowsBefore.keys.intersect(rowsAfter.keys).any { id ->
                    abs(rowsBefore.getValue(id).top - rowsAfter.getValue(id).top) > 1f
                }, "player rows move independently inside their pane at $viewport")

                val toComposer = room.snapshotBounds().getValue("chat-input").top - 8f
                room.pageScrollState.dispatchRawDelta(toComposer)
                room.render(4)
                val composer = room.snapshotBounds().getValue("chat-input")
                assertTrue(
                    composer.top >= 0f && composer.bottom <= viewport.height + 1f,
                    "the composer is reachable above history at $viewport"
                )
                assertTrue(
                    composer.height >= 48f * viewport.density,
                    "chat send row has a usable touch target at $viewport"
                )
                val toBottom = (room.pageScrollState.maxValue - room.pageScrollState.value).toFloat()
                if (toBottom > 0f) room.pageScrollState.dispatchRawDelta(toBottom)
                room.render(4)
                val bottom = room.snapshotBounds()
                assertTrue(
                    bottom.getValue("chat-input").bottom < bottom.getValue("chat-history").top + 1f,
                    "composer remains above the whole-page chat history at $viewport"
                )
                assertTrue(
                    bottom.getValue("header").top < top.getValue("header").top,
                    "header participates in whole-page scrolling at $viewport"
                )
                room.savePng(Path.of("${prefix}-bottom.png"))
            } finally {
                room.close()
            }
        }
    }

    @Test
    fun desktopRoomKeepsBoundedPanesAndRoutesMapControls() {
        listOf(Viewport(1280, 720), Viewport(1366, 768)).forEach { viewport ->
            assertFalse(viewport.compact, "$viewport should use the bounded Middle/Large workspace")
            val room = RenderedRoom(viewport)
            try {
                room.setContent()
                room.render(7)

                val bounds = room.snapshotBounds()
                assertScrollStateIsUnattached(
                    room.pageScrollState,
                    "desktop room fits its viewport without attaching a page scroll modifier at $viewport",
                )
                assertTrue(
                    room.chatHistoryScrollState.maxValue > 0,
                    "desktop chat history keeps its own scroll range at $viewport"
                )
                assertTrue(
                    "decorative-header" in bounds,
                    "desktop header keeps its decorative title/tag at $viewport"
                )
                assertTrue(
                    "roster-header" in bounds,
                    "desktop player pane keeps its roster heading at $viewport"
                )
                assertTrue(
                    "roster-columns" in bounds,
                    "desktop player pane keeps its full table header at $viewport"
                )
                assertMapLeftOfPlayers(bounds, viewport)
                assertTrue(
                    bounds.getValue("header").bottom <= bounds.getValue("map").top + 1f,
                    "desktop header stays above the bounded map/player row at $viewport"
                )
                assertTrue(
                    bounds.getValue("map").left >= 0f && bounds.getValue("map").right <= viewport.width + 1f &&
                            bounds.getValue("map").bottom <= viewport.height + 1f,
                    "desktop map pane stays bounded in the viewport at $viewport"
                )
                assertTrue(
                    bounds.getValue("players").right <= viewport.width + 1f &&
                            bounds.getValue("players").bottom <= viewport.height + 1f,
                    "desktop player table stays bounded in the viewport at $viewport"
                )
                assertTrue(
                    bounds.getValue("chat").bottom <= viewport.height + 1f &&
                            bounds.getValue("chat-input").bottom <= bounds.getValue("chat").bottom + 1f,
                    "desktop chat and composer remain inside their bounded pane at $viewport"
                )
                assertTrue(
                    bounds.getValue("chat-history").height < HISTORY_LINES * 14f * viewport.density,
                    "desktop history viewport is bounded while the supplied history exceeds it at $viewport"
                )
                val mapActions = bounds.getValue("map-actions")
                assertTrue(
                    mapActions.bottom <= bounds.getValue("map").bottom + 1f,
                    "normal desktop map actions remain inside the map pane at $viewport"
                )

                val prefix = Path.of("build/ui-regression/room-workspace-${viewport.label}")
                room.savePng(Path.of("${prefix}-top.png"))

                click(
                    room, Offset(
                        mapActions.left + mapActions.width * 0.25f,
                        mapActions.top + 21f * viewport.density
                    )
                )
                click(
                    room, Offset(
                        mapActions.left + mapActions.width * 0.75f,
                        mapActions.top + 21f * viewport.density
                    )
                )
                click(
                    room, Offset(
                        mapActions.left + mapActions.width * 0.5f,
                        mapActions.bottom - 22f * viewport.density
                    )
                )
                assertEquals(1, room.mapClicks, "desktop map picker action reaches its callback at $viewport")
                assertEquals(1, room.optionsClicks, "desktop room options action reaches its callback at $viewport")
                assertEquals(1, room.startClicks, "desktop Start action reaches its callback at $viewport")
                assertEquals(
                    0, room.pageScrollState.value,
                    "map action routing must not cause the desktop page to scroll at $viewport"
                )
                room.savePng(Path.of("${prefix}-bottom.png"))
            } finally {
                room.close()
            }
        }
    }

    private fun click(room: RenderedRoom, position: Offset) {
        room.scene.sendPointerEvent(PointerEventType.Move, position)
        room.scene.sendPointerEvent(PointerEventType.Press, position, button = PointerButton.Primary)
        room.scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        room.render(2)
    }

    private fun RenderedRoom.snapshotBounds(): Map<String, Bounds> = bounds.toMap()

    private fun assertMapLeftOfPlayers(bounds: Map<String, Bounds>, viewport: Viewport) {
        val map = bounds.getValue("map")
        val players = bounds.getValue("players")
        assertTrue(
            map.left >= 0f && players.left > map.left && map.right <= players.left + 2f,
            "map is on the left and players on the right at $viewport: map=$map players=$players"
        )
    }

    private fun assertScrollStateIsUnattached(state: ScrollState, message: String) {
        assertEquals(0, state.value, "$message (unattached state stays at its initial value)")
        assertEquals(
            Int.MAX_VALUE, state.maxValue,
            "$message (Compose keeps maxValue at Int.MAX_VALUE until a scroll modifier attaches)"
        )
    }

    @Test
    fun chatFocusAndDraftSurviveHistoryRefreshAndKeyboardSizedViewport() {
        val room = RenderedRoom(Viewport(1280, 720))
        try {
            room.setContent(); room.render(7)
            room.draft = "Move mom, attack, command"
            room.chatFocus.requestFocus(); room.render(4)
            assertTrue(room.chatFocused, "composer can receive focus")
            repeat(8) {
                room.extraHistory = "\nserver refresh $it"
                room.render(2)
                assertTrue(room.chatFocused, "server refresh must preserve the existing text input")
                assertEquals("Move mom, attack, command", room.draft)
            }
            room.scene.size = IntSize(1280, 360)
            room.render(5)
            assertTrue(room.chatFocused, "IME-sized content viewport does not replace the composer")
            assertEquals("Move mom, attack, command", room.draft)
        } finally {
            room.close()
        }
    }

    private fun assertSamePosition(
        before: Map<String, Bounds>,
        after: Map<String, Bounds>,
        key: String,
        viewport: Viewport,
    ) {
        val old = before.getValue(key)
        val current = after.getValue(key)
        assertTrue(
            abs(old.left - current.left) <= 1f && abs(old.top - current.top) <= 1f,
            "$key remains stationary when only the player list scrolls at $viewport: before=$old after=$current"
        )
    }

    private companion object {
        const val PLAYER_COUNT = 24
        const val HISTORY_LINES = 40
    }
}

private fun Modifier.probe(name: String, target: MutableMap<String, Bounds>): Modifier =
    onGloballyPositioned { coordinates -> target[name] = coordinates.rawBounds() }

private fun Modifier.probeRow(index: Int, target: MutableMap<Int, Bounds>): Modifier =
    onGloballyPositioned { coordinates -> target[index] = coordinates.rawBounds() }

private fun LayoutCoordinates.rawBounds(): Bounds {
    val position = positionInRoot()
    return Bounds(position.x, position.y, size.width.toFloat(), size.height.toFloat())
}
