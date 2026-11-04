package io.github.rwpp.ui

import io.github.rwpp.graphics.*
import io.github.rwpp.mixin.graphics.WorldProjectedShadowFrameMixin
import io.github.rwpp.mixin.graphics.WorldProjectedShadowMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class WorldProjectedShadowsTest {
    @Test
    fun buildingShadowFillsOpenFactoryCourtyardAndKeepsExteriorTransparent() {
        val width = 11;
        val height = 11
        val pixels = IntArray(width * height)
        // A U-shaped factory sprite whose courtyard is open to the outside, so a simple
        // enclosed-hole flood fill would incorrectly leave its centre unshadowed.
        for (y in 2..8) {
            pixels[y * width + 2] = -1; pixels[y * width + 8] = -1
        }
        for (x in 2..8) pixels[8 * width + x] = -1
        val filled = solidBuildingShadowPixels(pixels, width, height)
        assertEquals(255, filled[5 * width + 5] ushr 24)
        assertEquals(255, filled[2 * width + 5] ushr 24)
        assertEquals(0, filled[0] ushr 24)
        assertEquals(0, filled[5 * width] ushr 24)
        assertEquals(0, filled[10 * width + 5] ushr 24)
    }

    @Test
    fun solidBuildingShadowRetainsSlantedOutlineInsteadOfUsingBoundingRectangle() {
        val pixels =
            IntArray(81) { i -> if (kotlin.math.abs(i % 9 - 4) + kotlin.math.abs(i / 9 - 4) in 2..3) -1 else 0 }
        val filled = solidBuildingShadowPixels(pixels, 9, 9)
        assertEquals(255, filled[4 * 9 + 4] ushr 24)
        assertEquals(0, filled[1 * 9 + 1] ushr 24)
        assertEquals(0, filled[7 * 9 + 7] ushr 24)
        assertTrue(solidBuildingShadowPixels(IntArray(81), 9, 9).all { it == 0 })
    }

    @Test
    fun projectedShadowStaysUnderItsFootprintAndOnlyHeightStretchesIt() {
        val small = DesktopWorldProjectedShadows.projectedOffset(6f, 0f)
        val building = DesktopWorldProjectedShadows.projectedOffset(50f, 0f)
        val aircraft = DesktopWorldProjectedShadows.projectedOffset(10f, 30f)
        assertTrue(small.first > 0 && small.second > 0)
        assertEquals(small.first, small.second, "sun shadows must project diagonally down-right")
        // A wide building keeps a short anchor instead of a footprint-proportional displacement.
        assertTrue(building.first < 8f, "building shadow must stay attached: ${building.first}")
        assertTrue(building.first < small.first + 5f)
        assertTrue(aircraft.first > building.first, "altitude still separates a flying shadow")
        assertTrue(aircraft.first <= 32f)
        assertTrue(
            DesktopWorldProjectedShadows.shadowSoftness(30f) >
                    DesktopWorldProjectedShadows.shadowSoftness(0f)
        )
    }

    @Test
    fun nativeShadowIsOnlySuppressedThroughTheReplacementGuard() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldProjectedShadowMixin::class.java)
            val target = "com/corrodinggames/rts/game/units/OrderableUnit"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val calls = node.methods.single { it.name == "drawTurretImage" }.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
            assertTrue(calls.any { it.owner == "io/github/rwpp/mixin/graphics/WorldProjectedShadowMixin" && it.name == "shadow" })
            assertTrue(calls.any { it.name == "shouldRenderShadow" }, "Original fallback shadow rendering remains")
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun nativeWorldDrawClearsReplacementCoverageAtDrawGameTail() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldProjectedShadowFrameMixin::class.java)
            val target = "com/corrodinggames/rts/game/RWGameEngine"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val calls = node.methods.single {
                it.name == "drawGame" && it.desc == "(Lcom/corrodinggames/rts/gameFramework/render/CanvasLike;F)V"
            }.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(calls.any {
                it.owner == "io/github/rwpp/mixin/graphics/WorldProjectedShadowFrameMixin" && it.name == "clearCoveredUnits"
            })
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun coveredNativeShadowsAreIdentityBasedAndLimitedToOneDrawGame() {
        val coverage = ProjectedShadowCoverage<EqualUnit>()
        val covered = EqualUnit()
        val equalButDifferent = EqualUnit()

        coverage.beginDrawGame()
        coverage.mark(covered)
        assertTrue(coverage.shouldReplace(covered, enabled = true))
        assertFalse(coverage.shouldReplace(equalButDifferent, enabled = true))
        assertFalse(coverage.shouldReplace(covered, enabled = false))

        coverage.endDrawGame()
        assertFalse(coverage.shouldReplace(covered, enabled = true))
        coverage.beginDrawGame()
        assertFalse(coverage.shouldReplace(covered, enabled = true))
    }

    @Test
    fun shadowPassRestoresTheNativeAtlasAfterSuccessOrFailure() {
        val originalAtlas = Any()
        var atlas: Any? = originalAtlas

        val result = withTextureAtlasTemporarilyDisabled(
            readAtlas = { atlas },
            writeAtlas = { atlas = it }
        ) {
            assertNull(atlas)
            "drawn"
        }
        assertEquals("drawn", result)
        assertSame(originalAtlas, atlas)

        assertFailsWith<IllegalStateException> {
            withTextureAtlasTemporarilyDisabled(
                readAtlas = { atlas },
                writeAtlas = { atlas = it }
            ) {
                assertNull(atlas)
                error("simulated draw failure")
            }
        }
        assertSame(originalAtlas, atlas)
    }

    @Test
    fun nativeShaderReloadKeepsTheProjectedShadowFragmentAndRefreshesVertexSource() {
        var vertexSource = "previous vertex"
        var fragmentSource = DesktopWorldProjectedShadows.FRAGMENT_SHADER

        pinProjectedShadowFragmentAfterNativeReload(
            readNativeSources = {
                vertexSource = "reloaded native vertex"
                fragmentSource = "reloaded pass-through native fragment"
            },
            writeFragmentSource = { fragmentSource = it }
        )

        assertEquals("reloaded native vertex", vertexSource)
        assertEquals(DesktopWorldProjectedShadows.FRAGMENT_SHADER, fragmentSource)
    }

    private class EqualUnit {
        override fun equals(other: Any?): Boolean = other is EqualUnit
        override fun hashCode(): Int = 1
    }
}
