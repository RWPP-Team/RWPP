/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.rwpp.graphics.AeroRangeCircle
import io.github.rwpp.graphics.buildAeroRangeGroups
import io.github.rwpp.graphics.drawAeroRangeContours
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop uses a GPU-backed Skia surface. A valid placement preview adds one range group per
 * team, and modded builders can report an unlimited radius. The raster result must never contain
 * an opaque team-coloured block, while ordinary ranges must stay visible.
 */
class AeroRangeDesktopRasterTest : DesktopComposeGraphicsTest() {
    @Test
    fun placementPreviewWithUnlimitedRangesNeverPaintsOpaqueTeamColour() {
        val width = 320
        val height = 200
        val teamColours = listOf(
            Color(0xff0000ff), Color(0xff00ffff), Color(0xffff00ff), Color(0xffed7014)
        )
        val circles = buildList {
            teamColours.forEachIndexed { team, colour ->
                // checkPlacementPosition only runs when the building is affordable and placeable.
                add(AeroRangeCircle(Offset(160f, 100f), 900_000f, team, colour, "build"))
                add(AeroRangeCircle(Offset(160f, 100f), 100_000f, team, colour, "attack"))
                add(AeroRangeCircle(Offset(40f + team * 60f, 40f), 18f, team, colour, "attack"))
            }
        }
        val groups = buildAeroRangeGroups(circles)
        assertTrue(groups.isNotEmpty(), "Ordinary attack ranges must still be grouped")

        val pixels = raster(width, height, groups)
        val opaque = pixels.count { (it ushr 24) >= 200 }
        assertEquals(0, opaque, "No desktop frame may contain an opaque team-coloured area")

        val saturated = pixels.count { pixel ->
            val alpha = pixel ushr 24
            alpha >= 200 && (pixel and 0xff) > 180
        }
        assertEquals(0, saturated, "Unlimited and oversized ranges must never reach the tessellator")
        assertTrue(
            pixels.any { (it ushr 24) in 5..7 },
            "Normal range circles must keep their faint 0.025 fill"
        )
    }

    @Test
    fun radiusWithinTheProjectionLimitStaysRoundAndBeyondItIsRejected() {
        val width = 320
        val height = 200
        // A range wider than the viewport keeps its circular arc; only its bounding box is larger
        // than the canvas. Clamping that box per edge used to turn it into an ellipse.
        val partiallyVisible = buildAeroRangeGroups(
            listOf(AeroRangeCircle(Offset(160f, 100f), width * 1.5f, 0, Color(0xff0000ff), "build"))
        )
        assertEquals(1, partiallyVisible.size)
        val visiblePixels = raster(width, height, partiallyVisible)
        assertTrue(
            visiblePixels.any { it ushr 24 > 0 },
            "A partially visible circle must still paint its visible arc"
        )

        val rejected = buildAeroRangeGroups(
            listOf(
                AeroRangeCircle(Offset(160f, 100f), width * 9f, 0, Color(0xff0000ff), "build"),
                AeroRangeCircle(Offset(20f, 20f), width * 12f, 1, Color(0xffff00ff), "attack"),
            )
        )
        assertEquals(2, rejected.size, "Sampling accepts finite radii; the renderer rejects their projection")
        val pixels = raster(width, height, rejected)
        assertTrue(
            pixels.all { it == 0 },
            "A projected radius above the projection limit must not become GPU path geometry"
        )
    }

    @Test
    fun everyTeamGroupKeepsItsOwnOutlineWithoutAccumulatingAlpha() {
        val width = 160
        val height = 96
        val groups = buildAeroRangeGroups(
            listOf(
                AeroRangeCircle(Offset(40f, 48f), 20f, 0, Color(0xff0000ff)),
                AeroRangeCircle(Offset(58f, 48f), 20f, 0, Color(0xff0000ff)),
                AeroRangeCircle(Offset(120f, 48f), 20f, 1, Color(0xff00ffff)),
            )
        )
        val pixels = raster(width, height, groups, detailed = false)
        val interior = pixels[48 * width + 40]
        val overlap = pixels[48 * width + 49]
        assertEquals(interior, overlap, "A union must not darken its own overlap")
        assertTrue((interior ushr 24) in 5..7)
        assertEquals(2, groups.size, "Different teams remain separate groups")
    }

    private fun raster(
        width: Int,
        height: Int,
        groups: List<io.github.rwpp.graphics.AeroRangeGroup>,
        detailed: Boolean = false,
    ): IntArray = Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(0)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(),
            Size(width.toFloat(), height.toFloat())
        ) {
            drawAeroRangeContours(
                groups, Offset.Zero, 1f, width.toFloat(), height.toFloat(), 0f, detailed
            )
        }
        surface.makeImageSnapshot().use { image ->
            Bitmap().use { bitmap ->
                bitmap.allocPixels(image.imageInfo)
                check(image.readPixels(bitmap))
                IntArray(width * height) { index -> bitmap.getColor(index % width, index / width) }
            }
        }
    }
}
