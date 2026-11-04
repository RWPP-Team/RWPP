/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.rwpp.graphics.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path as FilePath

class AeroRangeContoursTest : DesktopComposeGraphicsTest() {
    @Test
    fun movingOwnersRefreshBetweenHeavySamplesWithoutMutatingPreviousFrame() {
        data class Owner(var center: Offset, var visible: Boolean = true)

        val moving = Owner(Offset(20f, 32f))
        val stationary = Owner(Offset(36f, 32f))
        val tracker = AeroRangeTracker<Owner>()
        tracker.add(moving, AeroRangeCircle(moving.center, 8f, 1, Color.Cyan))
        tracker.add(stationary, AeroRangeCircle(stationary.center, 8f, 1, Color.Cyan))
        fun frame() = tracker.frame { if (it.visible) it.center else null }
        val previous = frame()
        moving.center = Offset(55f, 32f)
        val current = frame()
        assertEquals(Offset(20f, 32f), previous.single().circles.first().center)
        assertEquals(moving.center, current.single().circles.first().center)
        assertEquals(stationary.center, current.single().circles.last().center)
        withRasterPixels(80, 64, current, detailed = false) { pixel ->
            assertEquals(0, pixel(20, 32), "Old moving-unit range must disappear immediately")
            assertTrue(pixel(55, 32) ushr 24 > 0, "New range center must render this frame")
        }
        moving.visible = false
        assertEquals(listOf(stationary.center), frame().single().circles.map { it.center })
        tracker.clear()
        assertTrue(frame().isEmpty())
    }

    @Test
    fun exportsTheActualShaderPreviewForVisualReview() {
        val groups = buildAeroRangeGroups(
            listOf(
                AeroRangeCircle(Offset(180f, 180f), 110f, 1, Color(0xff68d9e8)),
                AeroRangeCircle(Offset(320f, 180f), 110f, 1, Color(0xff68d9e8)),
                AeroRangeCircle(Offset(465f, 195f), 80f, 2, Color(0xffffa36d))
            )
        )
        Surface.makeRasterN32Premul(640, 360).use { surface ->
            surface.canvas.clear(0xff14202a.toInt())
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                surface.canvas.asComposeCanvas(),
                Size(640f, 360f)
            ) {
                for (x in 0..640 step 32) drawLine(
                    Color.White.copy(alpha = 0.025f),
                    Offset(x.toFloat(), 0f),
                    Offset(x.toFloat(), 360f)
                )
                for (y in 0..360 step 32) drawLine(
                    Color.White.copy(alpha = 0.025f),
                    Offset(0f, y.toFloat()),
                    Offset(640f, y.toFloat())
                )
                drawAeroRangeContours(groups, Offset.Zero, 1f, 640f, 360f, 0.35f, true)
            }
            val path = FilePath.of("build/ui-regression/range-mask-preview.png")
            Files.createDirectories(path.parent)
            surface.makeImageSnapshot().use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }
    }

    @Test
    fun groupsCirclesByTeamAndKindWithoutBooleanPathOperations() {
        val circles = listOf(
            AeroRangeCircle(Offset(0f, 0f), 10f, team = 1, color = Color.Red),
            AeroRangeCircle(Offset(12f, 0f), 10f, team = 1, color = Color.Red),
            AeroRangeCircle(Offset(0f, 0f), 10f, team = 2, color = Color.Blue),
            AeroRangeCircle(Offset(0f, 0f), 10f, team = 1, color = Color.Green, kind = "heal"),
            AeroRangeCircle(Offset(0f, 0f), 0f, team = 3, color = Color.Black),
            AeroRangeCircle(Offset(Float.NaN, 0f), 10f, team = 4, color = Color.Black),
        )

        val groups = buildAeroRangeGroups(circles)

        assertEquals(3, groups.size)
        assertEquals(listOf(1, 2, 1), groups.map { it.team })
        assertEquals(listOf("attack", "attack", "heal"), groups.map { it.kind })
        assertEquals(listOf(Color.Red, Color.Blue, Color.Green), groups.map { it.color })
        assertEquals(2, groups.first().circles.size)
        assertFalse(groups.any { group -> group.circles.any { it.radius <= 0f || !it.center.x.isFinite() } })
    }

    @Test
    fun maskShaderRendersOverlappingRangesOnceWithoutInternalOutline() {
        val width = 64
        val height = 64
        val color = Color(0xff58b7ff)
        val groups = buildAeroRangeGroups(
            listOf(
                AeroRangeCircle(Offset(20f, 32f), 18f, team = 1, color = color),
                AeroRangeCircle(Offset(36f, 32f), 18f, team = 1, color = color),
            ),
        )

        withRasterPixels(width, height, groups, detailed = false) { pixel ->
            // (8, 32) is inside only the first circle; (28, 32) lies in their overlap. At x=38
            // the first circle's old perimeter is internal to the second circle. All three
            // samples must have the same fill and there must be no former circle outline there.
            val singleCircleInterior = pixel(8, 32)
            assertTrue(singleCircleInterior ushr 24 > 0, "Range mask should render visible fill")
            assertTrue(
                (singleCircleInterior ushr 24) in 5..7,
                "Range interior should use the intentionally faint 0.025 fill alpha"
            )
            assertEquals(singleCircleInterior, pixel(28, 32), "Overlap alpha must equal one circle's fill")
            assertEquals(singleCircleInterior, pixel(38, 32), "Merged mask must not show an internal seam")
            assertEquals(0, pixel(0, 0), "Pixels outside the mask and edge glow must stay transparent")
        }
    }

    @Test
    fun cameraAndZoomAreAppliedInScreenSpaceWithoutRebuildingRangeGeometry() {
        val width = 80
        val height = 48
        val groups = buildAeroRangeGroups(
            listOf(AeroRangeCircle(Offset(110f, 70f), 10f, team = 1, color = Color.Cyan)),
        )
        val pixels = renderMask(
            width = width,
            height = height,
            groups = groups,
            camera = Offset(100f, 60f),
            zoom = 2f,
            worldWidth = 80f,
            worldHeight = 48f,
            detailed = true,
        )

        assertTrue(pixels[20 * width + 20] ushr 24 > 0, "World center should project through camera and zoom")
        assertEquals(0, pixels[0], "The camera projection must not shift the range to the origin")
    }

    /**
     * Writes a reproducible comparison to build/ui-regression/range-benchmark.txt. It has no
     * pass/fail speed threshold because native path-op and raster shader timings vary by machine.
     * The 1000-circle legacy workload deliberately uses only one warmup and three measured frames.
     */
    @Test
    fun recordsLegacyUnionVersusMaskRendererP50AndP95() {
        val report = StringBuilder()
        report.appendLine("Aero range renderer benchmark (fixed 1024x768 raster surface)")
        report.appendLine("Units: milliseconds; circles move by 0.25 world units between frames.")
        report.appendLine("The legacy path-op baseline rebuilds one union per frame and draws fill + stroke.")
        report.appendLine("The mask renderer rebuilds only group samples and composites one opaque mask per group.")
        report.appendLine()

        Surface.makeRasterN32Premul(1024, 768).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            val drawScope = CanvasDrawScope()
            for (count in listOf(250, 1000)) {
                val oldWarmups = if (count == 250) 1 else 1
                val oldSamples = if (count == 250) 7 else 3
                val maskWarmups = if (count == 250) 2 else 1
                val maskSamples = if (count == 250) 9 else 5
                val seedCircles = benchmarkCircles(count)
                val oldTimings = measureFrames(oldWarmups, oldSamples) { frame ->
                    val circles = moveCircles(seedCircles, frame)
                    val contours = legacyPathUnion(circles)
                    surface.canvas.clear(0)
                    drawScope.draw(
                        density = Density(1f), layoutDirection = LayoutDirection.Ltr,
                        canvas = canvas, size = Size(1024f, 768f),
                    ) {
                        contours.forEach { contour ->
                            drawPath(contour.first, contour.second.copy(alpha = contour.second.alpha * 0.12f))
                            drawPath(
                                contour.first, contour.second.copy(alpha = contour.second.alpha * 0.85f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)
                            )
                        }
                    }
                }
                val maskTimings = measureFrames(maskWarmups, maskSamples) { frame ->
                    val groups = buildAeroRangeGroups(moveCircles(seedCircles, frame))
                    surface.canvas.clear(0)
                    drawScope.draw(
                        density = Density(1f), layoutDirection = LayoutDirection.Ltr,
                        canvas = canvas, size = Size(1024f, 768f),
                    ) {
                        drawAeroRangeContours(
                            groups, Offset.Zero, zoom = 1f,
                            worldWidth = 1024f, worldHeight = 768f, phase = frame * 0.025f,
                            detailed = true
                        )
                    }
                }
                report.appendLine("circles=$count")
                report.appendLine("  legacy warmups=$oldWarmups samples=$oldSamples ${oldTimings.formatStats()}")
                report.appendLine("  mask   warmups=$maskWarmups samples=$maskSamples ${maskTimings.formatStats()}")
            }
        }

        val output = FilePath.of("build", "ui-regression", "range-benchmark.txt")
        Files.createDirectories(output.parent)
        Files.writeString(output, report.toString())
    }

    private fun renderMask(
        width: Int,
        height: Int,
        groups: List<AeroRangeGroup>,
        camera: Offset = Offset.Zero,
        zoom: Float = 1f,
        worldWidth: Float = width.toFloat(),
        worldHeight: Float = height.toFloat(),
        detailed: Boolean,
    ): IntArray = withRasterPixels(width, height, groups, camera, zoom, worldWidth, worldHeight, detailed) { pixel ->
        IntArray(width * height) { index -> pixel(index % width, index / width) }
    }

    private fun <T> withRasterPixels(
        width: Int,
        height: Int,
        groups: List<AeroRangeGroup>,
        camera: Offset = Offset.Zero,
        zoom: Float = 1f,
        worldWidth: Float = width.toFloat(),
        worldHeight: Float = height.toFloat(),
        detailed: Boolean,
        read: (pixel: (Int, Int) -> Int) -> T,
    ): T = Surface.makeRasterN32Premul(width, height).use { surface ->
        surface.canvas.clear(0)
        CanvasDrawScope().draw(
            density = Density(1f), layoutDirection = LayoutDirection.Ltr,
            canvas = surface.canvas.asComposeCanvas(), size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawAeroRangeContours(groups, camera, zoom, worldWidth, worldHeight, phase = 0f, detailed = detailed)
        }
        surface.makeImageSnapshot().use { image ->
            Bitmap().use { bitmap ->
                bitmap.allocPixels(image.imageInfo)
                check(image.readPixels(bitmap))
                read { x, y -> bitmap.getColor(x, y) }
            }
        }
    }

    private fun benchmarkCircles(count: Int): List<AeroRangeCircle> = List(count) { index ->
        // A regular, non-overlapping layout makes the old boolean operation deterministic and
        // keeps the benchmark's output size bounded while still exercising every circle.
        val columns = 40
        val x = 14f + (index % columns) * 25f
        val y = 14f + (index / columns) * 25f
        AeroRangeCircle(Offset(x, y), 7f, team = 1, color = Color(0xff58b7ff))
    }

    private fun moveCircles(circles: List<AeroRangeCircle>, frame: Int): List<AeroRangeCircle> {
        val offset = frame * 0.25f
        return circles.map { it.copy(center = Offset(it.center.x + offset, it.center.y)) }
    }

    private fun legacyPathUnion(circles: List<AeroRangeCircle>): List<Pair<Path, Color>> =
        circles.groupBy { it.team to it.kind }.values.map { group ->
            var path = circlePath(group.first())
            group.drop(1).forEach { circle -> path = Path.combine(PathOperation.Union, path, circlePath(circle)) }
            path to group.first().color
        }

    private fun circlePath(circle: AeroRangeCircle) = Path().apply {
        addOval(
            Rect(
                circle.center.x - circle.radius, circle.center.y - circle.radius,
                circle.center.x + circle.radius, circle.center.y + circle.radius
            )
        )
    }

    private fun measureFrames(warmups: Int, samples: Int, draw: (frame: Int) -> Unit): List<Long> {
        repeat(warmups) { draw(-warmups + it) }
        return List(samples) { sample ->
            val start = System.nanoTime()
            draw(sample)
            System.nanoTime() - start
        }
    }

    private fun List<Long>.formatStats(): String {
        val sorted = sorted()
        fun percentile(p: Double) = sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)] / 1_000_000.0
        return "p50=%.3f p95=%.3f".format(percentile(0.50), percentile(0.95))
    }
}
