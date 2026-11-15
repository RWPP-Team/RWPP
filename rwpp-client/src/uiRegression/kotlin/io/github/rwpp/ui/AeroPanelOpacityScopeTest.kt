@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.v2.AeroPanel
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface as SkiaSurface
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The background transparency slider exists for battlefield HUD panels. A modal dialog must be
 * opaque, otherwise terrain and native text stay legible through the alert copy.
 *
 * Opacity is measured by rendering the same panel over two very different backdrops and reading
 * how much of that difference survives: a translucent fill leaks it, an opaque fill hides it.
 */
class AeroPanelOpacityScopeTest : DesktopComposeGraphicsTest() {
    private val originalOpacity = HudOpacity.value

    @AfterTest
    fun restoreOpacity() {
        HudOpacity.value = originalOpacity
    }

    @Test
    fun dialogScopeHidesBackdropWhileHudPanelsFollowSlider() {
        HudOpacity.value = DEFAULT_HUD_PANEL_OPACITY
        val designedLeak = backdropLeak(override = null)

        HudOpacity.value = 0.1f
        val hudLeak = backdropLeak(override = null)
        val dialogLeak = backdropLeak(override = 1f)

        assertTrue(
            hudLeak > designedLeak * 3,
            "the slider must still move HUD panels over terrain: hud=$hudLeak designed=$designedLeak"
        )
        assertTrue(
            dialogLeak == 0,
            "a dialog must hide the backdrop entirely, not just the slider's share: $dialogLeak"
        )
    }

    @Test
    fun dialogFillIgnoresSliderAtBothExtremes() {
        val leaks = listOf(0f, 0.35f, 1f).map { slider ->
            HudOpacity.value = slider
            backdropLeak(override = 1f)
        }
        assertTrue(
            leaks.all { it == 0 },
            "an opaque dialog must not follow the slider: $leaks"
        )
    }

    /** Channel-summed backdrop bleed through the panel center; 0 means fully hidden. */
    private fun backdropLeak(override: Float?): Int {
        val warm = renderPanelCenter(override, Color(0xFFC04010))
        val cold = renderPanelCenter(override, Color(0xFF1040C0))
        return channelDistance(warm, cold)
    }

    private fun channelDistance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                abs((a and 0xFF) - (b and 0xFF))

    private fun renderPanelCenter(override: Float?, backdrop: Color): Int {
        val size = 160
        val dispatcher = RenderThreadDispatcher()
        val recomposer = FrameRecomposer(dispatcher)
        val scene = CanvasLayersComposeScene(
            recomposer,
            size = IntSize(size, size),
            density = Density(1f),
        )
        val surface = SkiaSurface.makeRasterN32Premul(size, size)
        try {
            scene.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().background(backdrop)) {
                        CompositionLocalProvider(LocalPanelAlphaOverride provides override) {
                            AeroPanel(Modifier.fillMaxSize().padding(20.dp)) {}
                        }
                    }
                }
            }
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
            var center = 0
            surface.makeImageSnapshot().use { image ->
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo)
                    assertTrue(image.readPixels(bitmap), "Skia should read the rendered AeroPanel")
                    center = bitmap.getColor(size / 2, size / 2)
                }
            }
            return center
        } finally {
            scene.close()
            recomposer.close()
            surface.close()
        }
    }
}
