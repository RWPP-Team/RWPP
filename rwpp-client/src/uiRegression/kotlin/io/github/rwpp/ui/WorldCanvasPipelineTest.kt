package io.github.rwpp.ui

import io.github.rwpp.graphics.WorldCanvasShaderSources
import io.github.rwpp.graphics.WorldTerrainShaderSources
import io.github.rwpp.graphics.WorldTextureFrame
import io.github.rwpp.graphics.worldSceneMaterialPixel
import io.github.rwpp.mixin.graphics.WorldCanvasTextureMixin
import io.github.rwpp.mixin.graphics.WorldWaterSurfaceDrawMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.jetbrains.skia.RuntimeEffect
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class WorldCanvasPipelineTest : DesktopComposeGraphicsTest() {
    @Test
    fun commonCanvasMaterialShaderCompilesAsSkSL() {
        for (source in listOf(
            WorldCanvasShaderSources.TERRAIN, WorldCanvasShaderSources.POST,
            WorldCanvasShaderSources.SHADOW, WorldCanvasShaderSources.UNIT_MASK, WorldCanvasShaderSources.BACKGROUND
        )) {
            RuntimeEffect.makeForShader(source).use { assertNotNull(it) }
        }
    }

    @Test
    fun nativeCanvasHooksMatchAllThreeActualBitmapDrawOverloads() {
        val name = "com/corrodinggames/rts/gameFramework/render/CanvasDirect"
        val original = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldCanvasTextureMixin::class.java)
            val transformed = AsmProcessor().transform(name, original, javaClass.classLoader)
            assertFalse(original.contentEquals(transformed))
            val node = org.objectweb.asm.tree.ClassNode()
            org.objectweb.asm.ClassReader(transformed).accept(node, 0)
            val calls = node.methods.flatMap { it.instructions.toArray().toList() }
                .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
                .filter { it.owner == "io/github/rwpp/mixin/graphics/WorldCanvasTextureMixin" }.map { it.name }.toSet()
            assertTrue(calls.containsAll(setOf("image", "rect", "rectF")), calls.toString())
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun finalMapHookIsAvailableToBothPlatformSourceSets() {
        val name = "com/corrodinggames/rts/game/RWGameEngine"
        val original = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(WorldWaterSurfaceDrawMixin::class.java)
            val transformed = AsmProcessor().transform(name, original, javaClass.classLoader)
            assertFalse(original.contentEquals(transformed))
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun qualityScopeRestoresAfterFailureAndNeverFiltersTheHud() {
        assertFalse(WorldTextureFrame.isFiltered())
        assertFailsWith<IllegalStateException> {
            WorldTextureFrame.draw(true) {
                assertTrue(WorldTextureFrame.isFiltered())
                WorldTextureFrame.draw(false) { assertFalse(WorldTextureFrame.isFiltered()) }
                assertTrue(WorldTextureFrame.isFiltered())
                error("failed native draw")
            }
        }
        assertFalse(WorldTextureFrame.isFiltered())
    }

    @Test
    fun terrainMaterialMaskAndShaderDoNotCullEffectsByFog() {
        val regular = worldSceneMaterialPixel(1f, 3f, 0f, false)
        val cliff = worldSceneMaterialPixel(1f, 3f, 0f, true)
        assertEquals(255, regular ushr 24, "Android material masks stay opaque through bitmap premultiplication")
        assertEquals(255, cliff ushr 24)
        assertEquals(0, regular and 255, "regular terrain does not carry cliff or visibility bits")
        assertEquals(64, cliff and 255, "cliff semantics remain independent of fog visibility")
        assertFalse(WorldTerrainShaderSources.FRAGMENT_SHADER.contains("visible"))
        assertFalse(WorldTerrainShaderSources.FRAGMENT_SHADER.contains("mix(original.rgb,shaded"))
        assertFalse(
            WorldCanvasShaderSources.TERRAIN.contains("visible"),
            "Android's shared shader dialect must not restore fog culling"
        )
    }

    @Test
    fun customUnitMaterialsStillUseNativeTeamVisibility() {
        val name = "io/github/rwpp/graphics/WorldSceneSprites"
        val node = ClassNode()
        javaClass.classLoader.getResourceAsStream("$name.class")!!.use { ClassReader(it).accept(node, 0) }
        val calls = node.methods.single { it.name == "visible" }.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
        assertTrue(
            calls.any { it.name == "isVisibleToTeam" },
            "terrain material lighting ignores fog, while custom unit sprites retain the engine visibility check"
        )
    }
}
