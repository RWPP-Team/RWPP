@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.widget.ResponsiveSelectionBody
import io.github.rwpp.widget.v2.AeroButton
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapSelectionRegressionTest : DesktopComposeGraphicsTest() {
    @Test
    fun tappingAnyCompactMapUpdatesSelectionOutlineAndShowsItsConfirmAction() {
        val maps = List(10) { index ->
            GameMap(
                id = index,
                mapName = "[skirmish]arena-$index.tmx",
                mapType = GameSetupMapType.skirmishMap,
                inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
            )
        }
        val targetIndex = 7
        val state = androidx.compose.foundation.lazy.LazyListState()
        val rowBounds = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
        val confirmBounds = mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        val selectedSnapshot = mutableStateOf(emptyList<Int>())
        val confirmedMap = mutableStateOf<Pair<Int, GameMap>?>(null)

        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(
            recomposer,
            size = IntSize(360, 520),
            density = Density(1f),
        )
        val surface = Surface.makeRasterN32Premul(360, 520)
        var frameTime = 0L

        fun render(frames: Int = 6): IntArray {
            repeat(frames) {
                dispatcher.drain()
                frameTime += 16_666_667L
                recomposer.performFrame(frameTime)
                dispatcher.drain()
                scene.measureAndLayout()
                surface.canvas.clear(0)
                scene.draw(surface.canvas.asComposeCanvas())
            }
            return surface.makeImageSnapshot().use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo)
                    assertTrue(image.readPixels(bitmap))
                    IntArray(bitmap.width * bitmap.height) { index ->
                        bitmap.getColor(index % bitmap.width, index / bitmap.width)
                    }
                }
            }
        }

        try {
            scene.setContent {
                MaterialTheme {
                    var selectedMapKey by remember { mutableStateOf(maps.first().mapSelectionKey()) }
                    var revealMapIndex by remember { mutableIntStateOf(-1) }
                    val selectedIndex = resolveMapSelectionIndex(maps, selectedMapKey, 0)

                    SideEffect {
                        selectedSnapshot.value = maps.indices.filter {
                            maps[it].mapSelectionKey() == selectedMapKey
                        }
                    }
                    LaunchedEffect(revealMapIndex) {
                        val index = revealMapIndex
                        if (index >= 0) {
                            state.animateScrollToItem(COMPACT_MAP_PAGE_HEADER_ITEMS + index)
                            revealMapIndex = -1
                        }
                    }

                    ResponsiveSelectionBody(
                        compact = true,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        compactState = state,
                        compactContent = {
                            item(key = "header") { Text("Map picker") }
                            item(key = "search") { Text("Search") }
                            item(key = "filter") { Text("Type") }
                            item(key = "list-heading") { Text("Maps") }
                            items(maps, key = { it.mapSelectionKey() }) { map ->
                                val index = maps.indexOf(map)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    MapSelectionButton(
                                        text = map.displayName(),
                                        selected = index == selectedIndex,
                                        modifier = Modifier.onGloballyPositioned {
                                            rowBounds[index] = it.boundsInRoot()
                                        },
                                        onClick = {
                                            selectedMapKey = map.mapSelectionKey()
                                            revealMapIndex = index
                                        },
                                    )
                                    if (index == selectedIndex) {
                                        AeroButton(
                                            text = "CONFIRM",
                                            modifier = Modifier.onGloballyPositioned {
                                                confirmBounds.value = it.boundsInRoot()
                                            },
                                            onClick = {
                                                val confirmedIndex = resolveMapSelectionIndex(maps, selectedMapKey, 0)
                                                confirmedMap.value = confirmedIndex to maps[confirmedIndex]
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        desktopContent = {},
                    )
                }
            }

            render()
            assertEquals(listOf(0), selectedSnapshot.value)

            // Model a long map archive: the target starts below the viewport, then is clicked after scrolling to it.
            state.requestScrollToItem(COMPACT_MAP_PAGE_HEADER_ITEMS + targetIndex)
            render()
            val targetRow = rowBounds.getValue(targetIndex)
            val beforeTap = render()

            val tap = Offset(targetRow.left + targetRow.width / 2f, targetRow.top + targetRow.height / 2f)
            scene.sendPointerEvent(PointerEventType.Move, tap)
            scene.sendPointerEvent(PointerEventType.Press, tap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, tap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(-10f, -10f))
            val afterTap = render(12)

            assertEquals(
                listOf(targetIndex), selectedSnapshot.value,
                "the selected-map snapshot must move to the map that received the pointer tap"
            )
            assertTrue(beforeTap.indices.count { beforeTap[it] != afterTap[it] } > 100,
                "selected map outline and its inline confirmation panel must visibly update")
            val confirm = assertNotNull(confirmBounds.value, "the selected map confirm action should render inline")
            assertTrue(
                confirm.top >= 0f && confirm.bottom <= 520f,
                "confirm action should be visible after the selected row is brought into view"
            )

            val confirmTap = Offset(confirm.left + confirm.width / 2f, confirm.top + confirm.height / 2f)
            scene.sendPointerEvent(PointerEventType.Move, confirmTap)
            scene.sendPointerEvent(PointerEventType.Press, confirmTap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, confirmTap, button = PointerButton.Primary)
            render()
            assertEquals(
                targetIndex to maps[targetIndex], confirmedMap.value,
                "confirm must return the selected map and its current filtered-list index"
            )
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    @Test
    fun stableMapSelectionSurvivesRefreshAndFilteredListReordering() {
        val selected = GameMap(
            id = 8,
            mapName = "[skirmish]selected.tmx",
            mapType = GameSetupMapType.skirmishMap,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
        )
        val other = GameMap(
            id = 2,
            mapName = "[skirmish]other.tmx",
            mapType = GameSetupMapType.skirmishMap,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
        )

        val refreshed = listOf(other, selected)
        assertEquals(0, resolveMapSelectionIndex(listOf(selected, other), selected.mapSelectionKey(), 0))
        assertEquals(
            1, resolveMapSelectionIndex(refreshed, selected.mapSelectionKey(), 0),
            "a refresh must keep the same map selected even when its list index moves"
        )
    }
}
