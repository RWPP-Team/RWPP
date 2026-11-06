@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.hud.AeroResourceHudLine
import io.github.rwpp.game.hud.AeroResourceHudSegment
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the merged status readout so its mod resource rows can be reviewed without a match. */
class ModResourceHudPreviewTest : DesktopComposeGraphicsTest() {
    // Native formatAmount(amount, true) output. One appendResourceInHUD chain is one row:
    // "hour -> minute -> second" and "KD -> point" never wrap onto their own lines.
    private val timeSegments = listOf(
        AeroResourceHudSegment("0:", null),
        AeroResourceHudSegment("0:", null),
        AeroResourceHudSegment("12", 0xffffd45c.toInt()),
    )
    private val scoreSegments = listOf(
        AeroResourceHudSegment("0", 0xff72d8f5.toInt()),
        AeroResourceHudSegment("1 200(+2)", null),
    )
    private val sampleRows = listOf(
        AeroResourceHudLine("0:0:12", null, secondaryTeam = false, segments = timeSegments),
        AeroResourceHudLine("01 200(+2)", null, secondaryTeam = false, segments = scoreSegments),
    )

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit): Int {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(recomposer, size = IntSize(width, height))
        val surface = Surface.makeRasterN32Premul(width, height)
        var time = 0L
        try {
            scene.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(Color(0xff101418)).padding(8.dp)) {
                        content()
                    }
                }
            }
            repeat(4) {
                dispatcher.drain(); time += 16_666_667L; recomposer.performFrame(time)
                dispatcher.drain(); scene.measureAndLayout(); scene.draw(surface.canvas.asComposeCanvas())
            }
            var colored = 0
            surface.makeImageSnapshot().use { image ->
                image.encodeToData()?.use { data ->
                    val dir = java.nio.file.Path.of("build/ui-regression")
                    java.nio.file.Files.createDirectories(dir)
                    java.nio.file.Files.write(dir.resolve(name), data.bytes)
                }
            }
            colored = countCoveredPixels(surface, width, height)
            return colored
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    private fun countCoveredPixels(surface: Surface, width: Int, height: Int): Int {
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocN32Pixels(width, height)
        surface.readPixels(bitmap, 0, 0)
        val pixels = bitmap.peekPixels() ?: return 0
        var covered = 0
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels.getColor(x, y)
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            if (r + g + b > 250) covered++
        }
        bitmap.close()
        return covered
    }

    @Test
    fun verticalRowsRenderNameValueAndIncome() {
        val covered = render("mod-resources-vertical.png", 360, 140) {
            Column(Modifier.fillMaxSize()) {
                AeroCustomResourceHud(sampleRows, horizontal = false)
            }
        }
        assertTrue(covered > 250, "Resource rows must paint visible text; covered=$covered")
    }

    @Test
    fun horizontalRowsShareOneLineWithoutWrapping() {
        val covered = render("mod-resources-horizontal.png", 360, 140) {
            Column(Modifier.fillMaxSize()) {
                AeroCustomResourceHud(sampleRows, horizontal = true)
            }
        }
        assertTrue(covered > 250, "Horizontal resource rows must paint visible text; covered=$covered")
    }
}
