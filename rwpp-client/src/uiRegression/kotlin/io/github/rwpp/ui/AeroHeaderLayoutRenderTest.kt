@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.v2.AeroHeader
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Surface as SkiaSurface

/** Verifies AeroHeader's real Compose measure and render behavior at narrow and roomy widths. */
class AeroHeaderLayoutRenderTest : DesktopComposeGraphicsTest() {
    @Test
    fun narrowHeaderKeepsSingleLineTitleAndDropsTagWhileWideHeaderShowsTag() {
        val title = "MISSION SELECTOR / CAMPAIGN ARCHIVE"
        val tag = "TACTICAL DATA / 12 MISSIONS"
        val narrowTagged = renderHeader(
            width = 240, title = title, tag = tag,
            output = Path.of("build/ui-regression/aero-header-narrow-tag-suppressed.png")
        )
        val narrowWithoutTag = renderHeader(width = 240, title = title, tag = null)
        assertTrue(
            narrowTagged.bounds.width >= 239f && narrowTagged.bounds.height in 1f..20f,
            "narrow header should fit its width and stay one line: ${narrowTagged.bounds}"
        )
        assertTrue(
            narrowTagged.pixels.contentEquals(narrowWithoutTag.pixels),
            "a tag that cannot fit must be omitted entirely while the title remains visible on one line"
        )

        val wideTagged = renderHeader(
            width = 640, title = title, tag = tag,
            output = Path.of("build/ui-regression/aero-header-wide-tag-visible.png")
        )
        val wideWithoutTag = renderHeader(width = 640, title = title, tag = null)
        assertTrue(
            wideTagged.bounds.height in 1f..20f,
            "wide header title and tag should still share one line: ${wideTagged.bounds}"
        )
        assertFalse(
            wideTagged.pixels.contentEquals(wideWithoutTag.pixels),
            "the tag should render when the measured title, tag and spacing fit"
        )
        assertTrue(
            differingPixels(wideTagged.pixels, wideWithoutTag.pixels) > 20,
            "the visible tag should contribute real rendered pixels"
        )
    }

    private data class RenderedHeader(val bounds: Rect, val pixels: IntArray)

    private fun renderHeader(width: Int, title: String, tag: String?, output: Path? = null): RenderedHeader {
        val height = 48
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(
            recomposer,
            size = IntSize(width, height),
            density = Density(1f),
        )
        val surface = SkiaSurface.makeRasterN32Premul(width, height)
        var bounds = Rect.Zero

        try {
            scene.setContent {
                MaterialTheme {
                    AeroHeader(
                        title = title,
                        tag = tag,
                        modifier = Modifier.width(width.dp).onGloballyPositioned { bounds = it.boundsInRoot() },
                    )
                }
            }
            renderFrames(scene, dispatcher, recomposer, surface)
            var renderedPixels = IntArray(width * height)
            surface.makeImageSnapshot().use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo)
                    assertTrue(image.readPixels(bitmap), "Skia should read the AeroHeader frame")
                    renderedPixels = IntArray(width * height) { index ->
                        bitmap.getColor(index % width, index / width)
                    }
                }
                if (output != null) {
                    Files.createDirectories(output.parent)
                    image.encodeToData()!!.use { Files.write(output, it.bytes) }
                }
            }
            return RenderedHeader(bounds, renderedPixels)
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }

    private fun renderFrames(
        scene: androidx.compose.ui.scene.ComposeScene,
        dispatcher: RenderThreadDispatcher,
        recomposer: FrameRecomposer,
        surface: SkiaSurface,
    ) {
        var frameTime = 0L
        repeat(5) {
            dispatcher.drain()
            frameTime += 16_666_667L
            recomposer.performFrame(frameTime)
            dispatcher.drain()
            scene.measureAndLayout()
            surface.canvas.clear(0)
            scene.draw(surface.canvas.asComposeCanvas())
        }
    }

    private fun differingPixels(first: IntArray, second: IntArray): Int {
        assertEquals(first.size, second.size)
        return first.indices.count { first[it] != second[it] }
    }
}
