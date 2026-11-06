package io.github.rwpp.ui

import android.graphics.PointF
import com.corrodinggames.rts.gameFramework.gui.InterfaceEngine
import io.github.rwpp.game.building.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class TerrainEdgeBuildAlignmentTest {
    @Test
    fun dragSamplesUseNativeHalfTileCadenceAndNativeGridSnap() {
        val samples = assertNotNull(
            terrainEdgeNativeDragSamples(
                startX = 3f,
                startY = 4f,
                endX = 23f,
                endY = 0f,
                originX = 100f,
                originY = 200f,
                nativeSampleStep = 5f,
                gridStep = 10f,
                snapToNativeGrid = { x, y -> PointF((x / 10f).toInt() * 10f, (y / 10f).toInt() * 10f) },
            )
        )

        assertEquals(0f, samples.dragAngleDegrees)
        assertEquals(10f, samples.gridStep)
        assertEquals(
            listOf(100f, 100f, 110f, 110f, 120f),
            samples.points.map { it.x },
            "native samples advance by halfTileWidth and snap through the map grid",
        )
        assertTrue(samples.points.all { it.y == 200f })
    }

    @Test
    fun nativeDragSamplesAreGeneratedOnDemand() {
        var snaps = 0
        val samples = assertNotNull(
            terrainEdgeNativeDragSamples(
                startX = 0f,
                startY = 0f,
                endX = 20_000f,
                endY = 0f,
                originX = 0f,
                originY = 0f,
                nativeSampleStep = 5f,
                gridStep = 10f,
                snapToNativeGrid = { x, y ->
                    snaps++
                    PointF(x, y)
                },
            )
        )

        assertEquals(1, snaps, "only the native snapped start is eager")
        assertEquals(0f to 0f, samples.points.iterator().next().let { it.x to it.y })
        assertEquals(2, snaps, "the first native sample is snapped only when requested")
    }

    @Test
    fun anIllegalNativeSampleSearchesPerpendicularAndLocksToTheFirstLegalSide() {
        val attempts = mutableListOf<PointF>()
        val samples = listOf(PointF(0f, 0f), PointF(10f, 0f))
        val resolved = terrainEdgeResolveNativeSamples(
            samples, dragAngleDegrees = 0f, gridStep = 10f,
            validateNativePlacement = { point ->
                attempts += PointF(point.x, point.y)
                when {
                    point.y == 0f -> null
                    point.y > 0f -> point
                    else -> point // would be legal, but should not be probed after the lock is set
                }
            })

        assertEquals(listOf(0f to 5f, 10f to 5f), resolved.pairs())
        assertFalse(attempts.any { it.y < 0f }, "after the first positive-side result, only that side is searched")
    }

    @Test
    fun aDirectlyBuildableSampleIsKeptAndResetsTheSideLock() {
        val attempts = mutableListOf<PointF>()
        val resolved = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 0f), PointF(10f, 0f), PointF(20f, 0f), PointF(30f, 0f)),
            dragAngleDegrees = 0f,
            gridStep = 10f,
            validateNativePlacement = { point ->
                attempts += PointF(point.x, point.y)
                when {
                    point.x == 20f -> point
                    point.y == 0f -> null
                    point.x < 20f && point.y > 0f -> point
                    point.x > 20f && point.y < 0f -> point
                    else -> null
                }
            },
        )

        assertEquals(listOf(0f to 5f, 10f to 5f, 20f to 0f, 30f to -5f), resolved.pairs())
        assertTrue(attempts.any { it.x == 20f && it.y == 0f }, "a legal native point is kept without projection")
        assertTrue(
            attempts.any { it.x == 30f && it.y == -5f },
            "the direct placement reset lets the next run search either side"
        )
    }

    @Test
    fun directlyLegalButOverlappingSampleStillResetsTheSideLock() {
        val resolved = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 0f), PointF(10f, 0f), PointF(20f, 0f)),
            dragAngleDegrees = 0f,
            gridStep = 10f,
            validateNativePlacement = { point ->
                when {
                    point.x == 10f && point.y == 0f -> point
                    point.x == 20f && point.y < 0f -> point
                    point.x == 0f && point.y > 0f -> point
                    else -> null
                }
            },
            conflictsWithEarlierDragPlacement = { point, _ -> point.x == 10f && point.y == 0f },
        )

        assertEquals(listOf(0f to 5f, 20f to -5f), resolved.pairs())
    }

    @Test
    fun blueprintConflictFromTheNativeValidatorIsSearchedPast() {
        val resolved = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 0f)),
            dragAngleDegrees = 0f,
            gridStep = 10f,
            validateNativePlacement = { point ->
                when {
                    point.y == 0f -> null // canPlaceAt rejects the blocked raw tile
                    point.y == 5f -> null // native Blueprint.isFootprintOverBlueprint rejects it
                    point.y == 10f -> point
                    else -> null
                }
            },
        )

        assertEquals(listOf(0f to 10f), resolved.pairs())
    }

    @Test
    fun previousDragPlacementsAreTreatedAsNativeBlueprintConflicts() {
        val resolved = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 5f), PointF(0f, 0f)),
            dragAngleDegrees = 0f,
            gridStep = 10f,
            validateNativePlacement = { if (it.y > 0f) it else null },
            conflictsWithEarlierDragPlacement = { point, earlier ->
                earlier.any { kotlin.math.hypot(it.x - point.x, it.y - point.y) < 9f }
            },
        )

        assertEquals(listOf(0f to 5f, 0f to 15f), resolved.pairs())
    }

    @Test
    fun sideLockIsLocalToOneDragInvocation() {
        val firstDrag = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 0f)), dragAngleDegrees = 0f, gridStep = 10f,
            validateNativePlacement = { if (it.y > 0f) it else null },
        )
        val secondAttempts = mutableListOf<PointF>()
        val secondDrag = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(5f, 0f)), dragAngleDegrees = 0f, gridStep = 10f,
            validateNativePlacement = { point ->
                secondAttempts += PointF(point.x, point.y)
                if (point.y < 0f) point else null
            },
        )

        assertEquals(listOf(0f to 5f), firstDrag.pairs())
        assertEquals(listOf(5f to -5f), secondDrag.pairs())
        assertTrue(secondAttempts.any { it.x == 5f && it.y == -5f }, "a new drag starts without the prior positive-side lock")
    }

    @Test
    fun failedSearchIsBoundedToEightGridUnitsAndHalfGridSteps() {
        val attempts = mutableListOf<PointF>()
        val resolved = terrainEdgeResolveNativeSamples(
            samples = listOf(PointF(0f, 0f)), dragAngleDegrees = 0f, gridStep = 10f,
            validateNativePlacement = { point -> attempts += PointF(point.x, point.y); null },
        )

        assertTrue(resolved.isEmpty())
        assertEquals(33, attempts.size, "one raw sample plus 16 half-grid offsets on both sides")
        assertTrue(attempts.all { kotlin.math.abs(it.y) <= 80f })
        assertTrue(attempts.any { it.y == 80f } && attempts.any { it.y == -80f })
    }

    @Test
    fun fiveDegreeAxisSnapUsesStableHorizontalAndVerticalSearchSides() {
        assertEquals(0f, terrainEdgeSnapAxisAngle(4.9f))
        assertEquals(90f, terrainEdgeSnapAxisAngle(94.9f))
        assertEquals(6f, terrainEdgeSnapAxisAngle(6f))
        assertEquals(0f to 1f, terrainEdgeSearchNormal(4f).let { it.x to it.y }, "horizontal searches down first")
        assertEquals(1f to 0f, terrainEdgeSearchNormal(92f).let { it.x to it.y }, "vertical searches right first")
        assertEquals(
            0f to 1f,
            terrainEdgeSearchNormal(30f).let { it.x to it.y },
            "diagonal drags still use axis-aligned vertical search"
        )
        assertEquals(
            1f to 0f,
            terrainEdgeSearchNormal(60f).let { it.x to it.y },
            "steeper drags still use axis-aligned horizontal search"
        )
    }

    @Test
    fun nativePlacementLimitMatchesTheEngineCommandCap() {
        var sampleChecks = 0
        val resolved = terrainEdgeResolveNativeSamples(
            samples = (0..40).map { PointF(it * 20f, 0f) },
            dragAngleDegrees = 0f,
            gridStep = 10f,
            validateNativePlacement = { sampleChecks++; it },
        )
        assertEquals(TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS, resolved.size)
        assertEquals(
            TERRAIN_EDGE_MAX_NATIVE_PLACEMENTS,
            sampleChecks,
            "the resolver stops once the native command cap is reached"
        )
    }

    @Test
    fun invalidSampleArraysAndGridStepsFailClosed() {
        assertTrue(terrainEdgeResolveNativeSamples(emptyList(), 0f, 10f, { it }).isEmpty())
        assertTrue(terrainEdgeResolveNativeSamples(listOf(PointF()), 0f, 0f, { it }).isEmpty())
        assertNull(
            terrainEdgeNativeDragSamples(
                0f, 0f, 10f, 10f, 0f, 0f, nativeSampleStep = 0f, gridStep = 10f,
            ) { itX, itY -> PointF(itX, itY) })
    }

    @Test
    fun nativeCandidateAndPreviewKeepTheirIntentionalRangeSplit() {
        val builder = Any()
        val (candidateRangeUnit, previewRangeUnit) = terrainEdgeNativeRangeUnits(builder)

        assertNull(candidateRangeUnit, "candidate coordinates must not vary with preview reachability")
        assertTrue(previewRangeUnit === builder, "the native preview still displays actual builder range")
    }

    @Test
    fun installedNativePlacementPathChecksCanPlaceAtAndBlueprintFootprints() {
        val target = ClassNode(Opcodes.ASM9)
        val classBytes =
            InterfaceEngine::class.java.getResourceAsStream("/com/corrodinggames/rts/gameFramework/gui/InterfaceEngine.class")
                ?: error("InterfaceEngine class bytes are missing from game-lib")
        classBytes.use { ClassReader(it).accept(target, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        val nativeLinePlacement = target.methods.single {
            it.name == "findPlacementPositionsAlongLine" && it.desc.startsWith("(Lcom/corrodinggames/rts/game/units/OrderableUnit;")
        }
        val nativePlacementCheck = target.methods.single {
            it.name == "checkPlacementPosition" && it.desc.startsWith("(Lcom/corrodinggames/rts/game/units/OrderableUnit;")
        }
        val lineCalls = nativeLinePlacement.instructions.filterIsInstance<MethodInsnNode>()
        val checkCalls = nativePlacementCheck.instructions.filterIsInstance<MethodInsnNode>()

        assertTrue(lineCalls.any { it.owner == target.name && it.name == "checkPlacementPosition" })
        assertTrue(checkCalls.any {
            it.owner == "com/corrodinggames/rts/game/units/OrderableUnit" && it.name == "canPlaceAt"
        })
        assertTrue(checkCalls.any {
            it.owner == "com/corrodinggames/rts/gameFramework/effects/Blueprint" && it.name == "isFootprintOverBlueprint"
        })
    }

    /** PointF has no value equality, so expectations compare coordinates. */
    private fun List<PointF>.pairs(): List<Pair<Float, Float>> = map { it.x to it.y }

}
