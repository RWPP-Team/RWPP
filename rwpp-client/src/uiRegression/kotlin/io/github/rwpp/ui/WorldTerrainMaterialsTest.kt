package io.github.rwpp.ui

import io.github.rwpp.graphics.terrainCoastDistances
import io.github.rwpp.graphics.terrainSignatureAuditDue
import io.github.rwpp.mixin.graphics.WorldTerrainCacheMaterialMixin
import io.github.rwpp.mixin.graphics.WorldTerrainMutationMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class WorldTerrainMaterialsTest {
    @Test
    fun pooledCoastWorkspaceMatchesFreshResultsAcrossTerrainChanges() {
        val width = 9;
        val height = 7
        val scratch = FloatArray(width * height)
        val output = FloatArray(width * height)
        for (pattern in 0..3) {
            val water = BooleanArray(width * height) { i ->
                when (pattern) {
                    0 -> i % width >= 4
                    1 -> true
                    2 -> false
                    else -> i % 3 == 0
                }
            }
            val fresh = terrainCoastDistances(water, width, height)
            val pooled = terrainCoastDistances(water, width, height, 6f, scratch, output)
            assertSame(output, pooled)
            assertContentEquals(fresh, pooled)
        }
    }

    @Test
    fun terrainSignaturesAreAuditedEverySixteenFramesWithTileWriteInvalidation() {
        assertFalse(terrainSignatureAuditDue(0))
        assertFalse(terrainSignatureAuditDue(15))
        assertTrue(terrainSignatureAuditDue(16))
        assertFalse(terrainSignatureAuditDue(17))
        assertTrue(terrainSignatureAuditDue(32))
        assertFailsWith<IllegalArgumentException> { terrainSignatureAuditDue(16, 0) }
    }

    @Test
    fun nativeSetTileHasAnActualTerrainInvalidationInjection() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldTerrainMutationMixin::class.java)
            val target = "com/corrodinggames/rts/game/map/MapLayer"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
            assertTrue(AsmProcessor().applyAsms(target, node))
            val calls =
                node.methods.single { it.name == "setTile" && it.desc == "(IILcom/corrodinggames/rts/game/map/MapTile;Z)V" }
                    .instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertTrue(calls.any {
                it.owner == "io/github/rwpp/mixin/graphics/WorldTerrainMutationMixin" && it.name == "markTerrainMutation"
            }, "every normal native setTile call path marks the affected terrain cache")
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun signedCoastDistancesFadeContinuouslyFromBothSides() {
        val w = 16;
        val h = 5
        val water = BooleanArray(w * h) { it % w >= 8 }
        val distance = terrainCoastDistances(water, w, h)
        assertEquals(-0.5f, distance[2 * w + 7])
        assertEquals(0.5f, distance[2 * w + 8])
        assertEquals(-6f, distance[2 * w])
        assertEquals(6f, distance[2 * w + 15])
        for (x in 1 until w) assertTrue(distance[2 * w + x] >= distance[2 * w + x - 1])
    }

    @Test
    fun narrowRiversAndInlandIslandsKeepTheirOwnBoundary() {
        val w = 9;
        val h = 9
        val river = BooleanArray(w * h) { it % w == 4 }
        val distance = terrainCoastDistances(river, w, h)
        assertEquals(0.5f, distance[4 * w + 4])
        assertEquals(-0.5f, distance[4 * w + 3])
        val island = BooleanArray(w * h) { true }.also { it[4 * w + 4] = false }
        val islandDistance = terrainCoastDistances(island, w, h)
        assertEquals(-0.5f, islandDistance[4 * w + 4])
        assertEquals(0.5f, islandDistance[4 * w + 5])
        assertTrue(islandDistance[0] > islandDistance[4 * w + 5])
        assertFailsWith<IllegalArgumentException> { terrainCoastDistances(BooleanArray(2), 3, 3) }
    }

    @Test
    fun productionMapCacheCompositingIsRedirectedIncludingBothFogFadeImages() {
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldTerrainCacheMaterialMixin::class.java)
            val target = "com/corrodinggames/rts/game/map/MapLayerBuffer"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
            val method = node.methods.single { it.name == "updateAndDraw" }
            val before = method.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
                it.name == "drawImage" && it.desc.contains("Landroid/graphics/RectF;")
            }
            assertTrue(before >= 3, "Native normal cache and both fog transition cache draws are covered")
            assertTrue(AsmProcessor().applyAsms(target, node))
            val after = method.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
                it.owner == "io/github/rwpp/mixin/graphics/WorldTerrainCacheMaterialMixin" && it.name == "drawTerrain"
            }
            assertEquals(before, after)
        } finally {
            AsmRegistry.clear()
        }
    }
}
