@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.game.replay.ParsedReplayItem
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.*
import org.jetbrains.skia.Bitmap
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface as SkiaSurface

class ResponsiveSelectionLayoutTest : DesktopComposeGraphicsTest() {
    private data class Viewport(val width: Int, val height: Int)
    private enum class PageKind(val fileName: String, val title: String) {
        MISSION("mission", "MISSIONS"),
        REPLAYS("replays", "REPLAYS"),
        MAPS("maps", "MAP ARCHIVE"),
    }

    private data class RenderEvidence(
        val compact: Boolean,
        val visibleRows: Int,
        val detailsVisibleAfterScroll: Boolean,
        val firstPane: Rect,
        val secondPane: Rect,
        val png: Path,
    )

    @Test
    fun compactPagesScrollAsOnePageAndKeepListEntriesReachable() {
        val viewports = listOf(
            Viewport(320, 568),
            Viewport(640, 360),
            Viewport(800, 450),
        )

        for (viewport in viewports) {
            for (page in PageKind.entries) {
                val evidence = renderPage(page, viewport)
                assertTrue(
                    evidence.compact,
                    "${page.title} should use the compact page at ${viewport.width}x${viewport.height}"
                )
                assertTrue(
                    evidence.visibleRows > 0,
                    "${page.title} must show at least one full list entry above the fold at " +
                            "${viewport.width}x${viewport.height}",
                )
                assertTrue(
                    evidence.detailsVisibleAfterScroll,
                    "${page.title} details must be reachable by scrolling the same compact page",
                )
                assertTrue(Files.size(evidence.png) > 1_000, "${page.title} should produce a real Compose PNG")
            }
        }
    }

    @Test
    fun middleAndLargeWindowsKeepDesktopSelectionPanes() {
        assertTrue(usesCompactSelectionLayout(WindowManager.Small))
        assertFalse(usesCompactSelectionLayout(WindowManager.Middle))
        assertFalse(usesCompactSelectionLayout(WindowManager.Large))

        for (viewport in listOf(Viewport(1024, 576), Viewport(1280, 720), Viewport(1366, 768))) {
            for (page in PageKind.entries) {
                val evidence = renderPage(page, viewport)
                assertFalse(
                    evidence.compact,
                    "${page.title} at ${viewport.width}x${viewport.height} should retain desktop controls",
                )
                assertTrue(evidence.firstPane.width > 0f && evidence.secondPane.width > 0f)
                assertTrue(evidence.firstPane.right < evidence.secondPane.left)
            }
        }
    }

    private fun renderPage(page: PageKind, viewport: Viewport): RenderEvidence {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(
            recomposer,
            size = IntSize(viewport.width, viewport.height),
            density = Density(1f),
        )
        val surface = SkiaSurface.makeRasterN32Premul(viewport.width, viewport.height)
        val compactState = LazyListState()
        val rowBounds = linkedMapOf<Int, Rect>()
        var collectingInitialRows = true
        var compact = false
        var pageBounds = Rect.Zero
        var headerBounds = Rect.Zero
        var detailsBounds = Rect.Zero
        var firstPane = Rect.Zero
        var secondPane = Rect.Zero
        val output = Path.of("build/ui-regression/${page.fileName}-${viewport.width}x${viewport.height}.png")

        try {
            scene.setContent {
                MaterialTheme {
                    if (page == PageKind.MAPS) {
                        // Match MapViewDialog's real Surface bounds before choosing its compact mode.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f).padding(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    compact = maxWidth < 700.dp || maxHeight < 400.dp
                                    SelectionPageFixture(
                                        page = page,
                                        compact = compact,
                                        listState = compactState,
                                        rowBounds = rowBounds,
                                        collectInitialRows = { collectingInitialRows },
                                        onPageBounds = { pageBounds = it },
                                        onHeaderBounds = { headerBounds = it },
                                        onDetailsBounds = { detailsBounds = it },
                                        onFirstPaneBounds = { firstPane = it },
                                        onSecondPaneBounds = { secondPane = it },
                                    )
                                }
                            }
                        }
                    } else {
                        AeroScaledContainer {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val window = constraintWindowManager(maxWidth, maxHeight)
                                compact = usesCompactSelectionLayout(window)
                                AeroPanel(Modifier.fillMaxSize()) {
                                    SelectionPageFixture(
                                        page = page,
                                        compact = compact,
                                        listState = compactState,
                                        rowBounds = rowBounds,
                                        collectInitialRows = { collectingInitialRows },
                                        onPageBounds = { pageBounds = it },
                                        onHeaderBounds = { headerBounds = it },
                                        onDetailsBounds = { detailsBounds = it },
                                        onFirstPaneBounds = { firstPane = it },
                                        onSecondPaneBounds = { secondPane = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            var frameTime = renderFrames(scene, dispatcher, recomposer, surface)
            assertTrue(pageBounds.width > 0f && pageBounds.height > 0f)
            if (compact) {
                assertTrue(headerBounds.height > 0f, "the compact header should be in the page's scroll content")
            }

            val visibleRows = rowBounds.values.count { bounds ->
                bounds.width > 0f && bounds.height > 0f &&
                        bounds.top >= pageBounds.top && bounds.bottom <= pageBounds.bottom &&
                        bounds.top >= 0f && bounds.bottom <= viewport.height.toFloat()
            }
            writePngAndCheck(surface, output, page.title)

            var detailsReachable = false
            if (compact) {
                collectingInitialRows = false
                compactState.requestScrollToItem(detailsItemIndex(page))
                frameTime = renderFrames(scene, dispatcher, recomposer, surface, frameTime)
                detailsReachable = detailsBounds.width > 0f && detailsBounds.height > 0f &&
                        detailsBounds.top >= pageBounds.top && detailsBounds.bottom <= pageBounds.bottom &&
                        detailsBounds.top >= 0f && detailsBounds.bottom <= viewport.height.toFloat()
            }
            return RenderEvidence(compact, visibleRows, detailsReachable, firstPane, secondPane, output)
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    @Composable
    private fun SelectionPageFixture(
        page: PageKind,
        compact: Boolean,
        listState: LazyListState,
        rowBounds: MutableMap<Int, Rect>,
        collectInitialRows: () -> Boolean,
        onPageBounds: (Rect) -> Unit,
        onHeaderBounds: (Rect) -> Unit,
        onDetailsBounds: (Rect) -> Unit,
        onFirstPaneBounds: (Rect) -> Unit,
        onSecondPaneBounds: (Rect) -> Unit,
    ) {
        val sampleMap = remember {
            GameMap(
                id = 1,
                mapName = "[skirmish]arena.tmx",
                mapType = GameSetupMapType.skirmishMap,
                inputStreamProvider = { ByteArrayInputStream(byteArrayOf()) },
            )
        }
        val replayItems = remember(sampleMap) {
            List(12) { index ->
                ParsedReplayItem(
                    rawReplay = Replay(index, "Arena ${index + 1}"),
                    id = index,
                    fileName = "arena-${index + 1}.replay",
                    mapName = "Arena ${index + 1}",
                    map = sampleMap,
                    playerCountStr = "1 / SKIRMISH",
                    versionStr = "1.0",
                    dateTimeStr = "12:00",
                )
            }
        }

        ResponsiveSelectionBody(
            compact = compact,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { onPageBounds(it.boundsInRoot()) },
            compactState = listState,
            compactContent = {
                item(key = "fixture-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { onHeaderBounds(it.boundsInRoot()) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AeroHeader(title = page.title, modifier = Modifier.weight(1f))
                        AeroButton(text = "EXIT", onClick = {}, modifier = Modifier.width(72.dp))
                    }
                }
                if (page != PageKind.MISSION) {
                    item(key = "fixture-search") {
                        var search by remember { androidx.compose.runtime.mutableStateOf("") }
                        AeroTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = if (page == PageKind.REPLAYS) "SEARCH_MAP_OR_DATE..." else "SEARCH_MAPS...",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                when (page) {
                    PageKind.MISSION -> {
                        item(key = "fixture-type-filter") {
                            ResponsiveFilterChoices("TYPE", listOf("NORMAL", "CHALLENGE", "SURVIVAL"), 0, {})
                        }
                        item(key = "fixture-level-filter") {
                            ResponsiveFilterChoices("LEVEL", listOf("EASY", "MEDIUM", "HARD"), 1, {})
                        }
                    }

                    PageKind.REPLAYS -> {
                        item(key = "fixture-map-filter") {
                            ResponsiveFilterChoices("MAP", listOf("NONE", "SELECT MAP..."), 0, {})
                        }
                        item(key = "fixture-time-filter") {
                            ResponsiveFilterChoices("TIME", listOf("ALL TIME", "TODAY", "THIS WEEK"), 0, {})
                        }
                        item(key = "fixture-sort-filter") {
                            ResponsiveFilterChoices("SORT", listOf("NEWEST", "OLDEST", "MAP NAME"), 0, {})
                        }
                    }

                    PageKind.MAPS -> {
                        item(key = "fixture-type-filter") {
                            ResponsiveFilterChoices("TYPE", listOf("SKIRMISH", "CUSTOM", "SAVED"), 0, {})
                        }
                    }
                }
                if (page != PageKind.MISSION) {
                    item(key = "fixture-list-heading") { AeroHeader(title = "${page.title} · 12") }
                }
                items(count = 12, key = { "${page.name}-$it" }) { index ->
                    Box(
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                            if (collectInitialRows()) rowBounds[index] = it.boundsInRoot()
                        },
                    ) {
                        if (page == PageKind.REPLAYS) {
                            ReplayCardItem(
                                item = replayItems[index],
                                isSelected = index == 0,
                                compact = true,
                                onClick = {},
                            )
                        } else {
                            AeroButton(
                                text = if (page == PageKind.MISSION) "MISSION ${index + 1}" else "Map ${index + 1}",
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                item(key = "fixture-details") {
                    AeroPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                text = "SELECTED ${page.title} DETAILS",
                                modifier = Modifier.onGloballyPositioned { onDetailsBounds(it.boundsInRoot()) },
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(120.dp))
                            AeroButton(text = "OPEN", onClick = {})
                        }
                    }
                }
            },
            desktopContent = {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AeroPanel(
                        modifier = Modifier.weight(0.45f).fillMaxHeight()
                            .onGloballyPositioned { onFirstPaneBounds(it.boundsInRoot()) },
                    ) {
                        Text("${page.title} LIST", modifier = Modifier.align(Alignment.Center))
                    }
                    AeroPanel(
                        modifier = Modifier.weight(0.55f).fillMaxHeight()
                            .onGloballyPositioned { onSecondPaneBounds(it.boundsInRoot()) },
                    ) {
                        Text("${page.title} DETAILS", modifier = Modifier.align(Alignment.Center))
                    }
                }
            },
        )
    }

    private fun detailsItemIndex(page: PageKind): Int {
        val itemsBeforeRows = when (page) {
            PageKind.MISSION -> 3
            PageKind.REPLAYS -> 6
            PageKind.MAPS -> 4
        }
        return itemsBeforeRows + 12
    }

    private fun renderFrames(
        scene: androidx.compose.ui.scene.ComposeScene,
        dispatcher: RenderThreadDispatcher,
        recomposer: FrameRecomposer,
        surface: SkiaSurface,
        initialFrameTime: Long = 0L,
    ): Long {
        var frameTime = initialFrameTime
        repeat(5) {
            dispatcher.drain()
            frameTime += 16_666_667L
            recomposer.performFrame(frameTime)
            dispatcher.drain()
            scene.measureAndLayout()
            surface.canvas.clear(0)
            scene.draw(surface.canvas.asComposeCanvas())
        }
        return frameTime
    }

    private fun writePngAndCheck(surface: SkiaSurface, path: Path, title: String) {
        surface.makeImageSnapshot().use { image ->
            Bitmap().use { bitmap ->
                bitmap.allocPixels(image.imageInfo)
                assertTrue(image.readPixels(bitmap), "Skia should read the $title preview pixels")
                val opaquePixels = (0 until bitmap.height step 8).sumOf { y ->
                    (0 until bitmap.width step 8).count { x -> bitmap.getColor(x, y) ushr 24 > 0 }
                }
                assertTrue(opaquePixels > 20, "$title preview should contain rendered UI")
            }
            Files.createDirectories(path.parent)
            image.encodeToData()!!.use { Files.write(path, it.bytes) }
        }
    }
}
