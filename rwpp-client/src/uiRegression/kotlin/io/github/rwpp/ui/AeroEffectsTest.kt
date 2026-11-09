@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.hideFullHealthBarDuringMultiSelection
import io.github.rwpp.game.hud.hudActionAvailable
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.graphics.*
import io.github.rwpp.logic.hud.NativeHudVisibility
import io.github.rwpp.mixin.hud.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.*

class AeroEffectsTest : DesktopComposeGraphicsTest() {
    @Test
    fun pathsAreClippedBeforeGeneratingFlowGeometry() {
        val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, 640f, 360f)
        assertEquals(
            Offset(0f, 180f) to Offset(640f, 180f),
            clipAeroSegment(Offset(-100000f, 180f), Offset(100000f, 180f), bounds)
        )
        assertNull(clipAeroSegment(Offset(-100f, -5f), Offset(1000f, -5f), bounds))
        assertEquals(
            Offset(10f, 10f) to Offset(10f, 10f),
            clipAeroSegment(Offset(10f, 10f), Offset(10f, 10f), bounds)
        )
        assertNull(clipAeroSegment(Offset(Float.NaN, 0f), Offset.Zero, bounds))
    }

    @Test
    fun worldEffectsRenderFlowAndDamageWithoutTakingPointerInput() {
        val dispatcher = RenderThreadDispatcher()
        val recomposer = androidx.compose.ui.platform.FrameRecomposer(dispatcher)
        val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(
            recomposer,
            size = androidx.compose.ui.unit.IntSize(640, 360)
        )
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(640, 360)
        val state = androidx.compose.runtime.mutableStateOf(
            WorldVisuals(
                selected = listOf(
                    SelectedVisual(
                        Offset(420f, 80f), 18f, 1f, 1f, 0f, androidx.compose.ui.graphics.Color.Cyan,
                        selected = false, healthVisible = false, secondary = 0.5f, secondaryRecharging = true
                    ),
                    SelectedVisual(Offset(110f, 100f), 22f, 0.35f, 0.8f, 0.5f, androidx.compose.ui.graphics.Color.Cyan),
                    SelectedVisual(
                        Offset(60f, 260f),
                        20f,
                        0.4f,
                        0.8f,
                        0f,
                        androidx.compose.ui.graphics.Color.Cyan,
                        selected = false
                    ),
                    SelectedVisual(
                        Offset(90f, 300f), 18f, 1f, 1f, 0f, androidx.compose.ui.graphics.Color.Cyan,
                        selected = false, healthVisible = false, construction = 0.4f, secondary = 0.6f
                    ),
                    SelectedVisual(
                        Offset(600f, 60f), 18f, 1f, 1f, 0.5f, androidx.compose.ui.graphics.Color.Cyan,
                        selected = false, healthVisible = false
                    ),
                    SelectedVisual(Offset(540f, 60f), 18f, 1f, 1f, 0.5f, androidx.compose.ui.graphics.Color.Cyan)
                ),
                orders = listOf(
                    AeroOrder(Offset(140f, 140f), Offset(520f, 270f), true, 12),
                    AeroOrder(Offset(270f, 160f), Offset(410f, 90f), false, kind = AeroOrderKind.RALLY)
                ),
                production = listOf(
                    AeroProductionVisual(
                        Offset(330f, 320f), 22f, "重型坦克", 18, 4, 0.4f, false,
                        androidx.compose.ui.graphics.Color.Green
                    )
                ), width = 640f, height = 360f,
                pausedLabel = "暂停", waitingLabel = "等待"
            )
        )
        var time = 0L
        fun render(): IntArray {
            repeat(4) {
                dispatcher.drain(); time += 16_666_667L; recomposer.performFrame(time); dispatcher.drain()
                scene.measureAndLayout(); surface.canvas.clear(0)
                scene.draw(surface.canvas.asComposeCanvas())
            }
            return surface.makeImageSnapshot().use { image ->
                org.jetbrains.skia.Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo); image.readPixels(bitmap)
                    IntArray(640 * 360) { bitmap.getColor(it % 640, it / 640) }
                }
            }
        }
        try {
            scene.setContent { androidx.compose.material3.MaterialTheme { AeroWorldEffectSurface(state.value) } }
            val first = render()
            assertTrue(first.any { it == 0xff8b929b.toInt() }, "Recharging energy uses a gray row")
            assertTrue(first.any { it == 0xffffcb74.toInt() }, "Usable secondary energy keeps its warm row")
            state.value = state.value.copy(phase = 0.5f)
            val second = render()
            assertTrue(first.count { it ushr 24 > 0 } > 300)
            assertTrue(first[284 * 640 + 45] ushr 24 > 0, "Unselected damaged units still receive Aero bars")
            assertEquals(0, first[240 * 640 + 40], "Unselected units must not receive selected brackets")
            assertTrue(first[322 * 640 + 75] ushr 24 > 0, "Unselected construction progress stays visible")
            val shieldBlue = 0xff64ccff.toInt()
            assertEquals(shieldBlue, first[82 * 640 + 590], "Unselected shield is the same pale blue row")
            assertEquals(shieldBlue, first[87 * 640 + 530], "Selected shield uses the same color")
            assertEquals(
                36, (80..90).sumOf { y -> (580..620).count { x -> first[y * 640 + x] == shieldBlue } },
                "Only one two-pixel shield row is drawn"
            )
            assertTrue(first.indices.count { first[it] != second[it] } > 100, "Flow must move between frames")
            assertFalse(
                scene.sendPointerEvent(
                    androidx.compose.ui.input.pointer.PointerEventType.Press, Offset(200f, 180f),
                    button = androidx.compose.ui.input.pointer.PointerButton.Primary
                ).anyChangeConsumed
            )
            Files.createDirectories(Path.of("build/ui-regression"))
            surface.makeImageSnapshot().use {
                it.encodeToData()!!
                    .use { data -> Files.write(Path.of("build/ui-regression/aero-world-effects.png"), data.bytes) }
            }
        } finally {
            scene.close(); recomposer.close(); surface.close()
        }
    }

    @Test
    fun unselectedAndHoveredHealthUsesTheSameStyleAndRespectsVisibility() {
        assertTrue(aeroHealthVisible(false, false, true, 0.4f, selectedUnitCount = 1))
        assertTrue(aeroHealthVisible(false, true, false, 1f, selectedUnitCount = 1))
        assertFalse(aeroHealthVisible(false, false, false, 0.4f, selectedUnitCount = 1))
        assertFalse(aeroHealthVisible(false, false, true, 1f, selectedUnitCount = 1))
    }

    @Test
    fun multiSelectionHidesOnlyFullHealthBarsAndThinsTheSelectionOutline() {
        assertTrue(aeroHealthVisible(true, false, true, 1f, selectedUnitCount = 1))
        assertFalse(aeroHealthVisible(true, false, true, 1f, selectedUnitCount = 2))
        assertTrue(aeroHealthVisible(true, false, true, 0.7f, selectedUnitCount = 2))
        assertTrue(aeroHealthVisible(false, true, false, 1f, selectedUnitCount = 2))
        assertTrue(aeroHealthVisible(false, false, true, 0.7f, selectedUnitCount = 2))

        assertFalse(hideFullHealthBarDuringMultiSelection(false, 2, 1f, 1f))
        assertFalse(hideFullHealthBarDuringMultiSelection(true, 1, 1f, 1f))
        assertFalse(hideFullHealthBarDuringMultiSelection(true, 2, 0.99f, 1f))
        assertTrue(hideFullHealthBarDuringMultiSelection(true, 2, 1f, 1f))
        assertTrue(EntityRangeUnitComp.isNativeHealthBarPaintColor(0xC8B72C2C.toInt()))
        assertTrue(EntityRangeUnitComp.isNativeHealthBarPaintColor(0x78FF3C3C.toInt()))
        assertTrue(EntityRangeUnitComp.isNativeHealthBarPaintColor(0x64E8D01A.toInt()))
        assertFalse(EntityRangeUnitComp.isNativeHealthBarPaintColor(0xC817B3CF.toInt()))
        assertFalse(EntityRangeUnitComp.isNativeHealthBarPaintColor(0xF00000FF.toInt()))
        assertEquals(0.8f, AERO_DRAG_SELECTION_STROKE_WIDTH)
    }

    @Test
    fun queuedBuildGuardAndPatrolKeepTheirMeaningWhenGrouped() {
        assertEquals(AeroOrderKind.GUARD, aeroOrderKind("guardAt"))
        assertEquals(AeroOrderKind.GUARD, aeroOrderKind("follow"))
        assertEquals(AeroOrderKind.BUILD, aeroOrderKind("build"))
        assertEquals(AeroOrderKind.REPAIR, aeroOrderKind("repair"))
        assertEquals(AeroOrderKind.PATROL, aeroOrderKind("patrol"))
        val base = AeroOrder(Offset.Zero, Offset(100f, 100f), false)
        val orders = listOf(
            base, base.copy(step = 1), base.copy(kind = AeroOrderKind.BUILD),
            base.copy(kind = AeroOrderKind.GUARD), base.copy(team = 1), base.copy(preview = true)
        )
        assertEquals(6, aeroOrderGroups(orders, true).size)
    }

    @Test
    fun oldSettingsLoadAndAllNewEffectsCanBeSavedIndependently() {
        val old = net.peanuuutz.tomlkt.Toml.decodeFromString(Settings.serializer(), "hideOriginalHud = true")
        assertTrue(old.hideOriginalHud)
        assertFalse(old.aeroSelection)
        assertFalse(old.aeroOrderEffects)
        assertFalse(old.aeroDamageNumbers)
        assertTrue(old.smoothGameTextures)
        assertFalse(old.enhancedWorldRendering)
        old.hudAnimations = false; old.hudChatShowTeam = true; old.hudChatShowTimestamp = true
        old.aeroSelection = true; old.aeroDamageAnimation = false
        old.aeroOrderEffects = true; old.aeroGroupOrders = false
        old.aeroDamageNumbers = true; old.smoothGameTextures = false; old.enhancedWorldRendering = true
        old.aeroEnemyRangesOnly = true; old.aeroRallyPointEffects = true
        old.aeroProductionLabels = true; old.aeroAlliedProductionLabels = false
        val loaded = net.peanuuutz.tomlkt.Toml.decodeFromString(
            Settings.serializer(),
            net.peanuuutz.tomlkt.Toml.encodeToString(Settings.serializer(), old)
        )
        assertEquals(old, loaded)
    }

    @Test
    fun everyLiteralProductTranslationUsedBySettingsExistsInBothLanguages() {
        val source = Files.readString(Path.of("src/commonMain/kotlin/io/github/rwpp/ui/Settings.kt"))
        val keys = Regex("readI18n\\(\"(rwpp\\.[^\"$]+)\"").findAll(source).map { it.groupValues[1] }.toList()
        for (language in listOf("en", "zh")) {
            val properties = Properties().apply {
                Files.newBufferedReader(Path.of("src/commonMain/composeResources/files/rwpp_$language.properties"))
                    .use(::load)
            }
            keys.forEach { assertNotNull(properties.getProperty(it), "$language: $it") }
            assertNotNull(properties.getProperty("rwpp.settings.showWelcomeMessage"))
        }
    }

    @Test
    fun globalPatrolAndGuardDoNotRequireAPerUnitSpecialAction() {
        assertTrue(hudActionAvailable(com.corrodinggames.rts.game.units.actions.PatrolAction()) { error("Must not use per-unit lookup") })
        assertTrue(hudActionAvailable(com.corrodinggames.rts.game.units.actions.GuardAction()) { error("Must not use per-unit lookup") })
    }

    @Test
    fun groupFlowsMergeSameOrdersButKeepAttackAndMoveSeparate() {
        val commands = listOf(
            AeroOrder(Offset(0f, 0f), Offset(100f, 100f), false),
            AeroOrder(Offset(20f, 10f), Offset(100f, 100f), false),
            AeroOrder(Offset(20f, 10f), Offset(100f, 100f), true),
            AeroOrder(Offset(20f, 10f), Offset(300f, 300f), false)
        )
        val grouped = aeroOrderGroups(commands, true)
        assertEquals(3, grouped.size)
        assertEquals(Offset(10f, 5f), grouped.first().from)
        assertEquals(2, grouped.first().count)
        assertEquals(commands, aeroOrderGroups(commands, false))
    }

    @Test
    fun damageTrailDecaysWithoutFallingBelowHealthAndHealingSnapsUpward() {
        val hit = damageTrail(1f, 0.4f, 0.016f)
        assertTrue(hit in 0.9f..1f)
        var trail = hit
        repeat(100) { trail = damageTrail(trail, 0.4f, 0.016f) }
        assertEquals(0.4f, trail)
        assertEquals(1f, damageTrail(trail, 1f, 0.016f))
    }

    @Test
    fun nativeHudMixinsTransformTheActualGameClassesAndFpsConditionKeepsPreferences() {
        AsmRegistry.clear()
        try {
            listOf(
                NativeFpsHudMixin::class.java, NativeResourceHudMixin::class.java,
                NativeBattleHudMixin::class.java, NativeMinimapHudMixin::class.java,
                AeroNativeSelectionMixin::class.java
            ).forEach(AsmRegistry::register)
            for (target in listOf(
                "com/corrodinggames/rts/game/RWGameEngine", "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine",
                "com/corrodinggames/rts/gameFramework/gui/BattleInterface",
                "com/corrodinggames/rts/gameFramework/gui/MinimapHandler", "com/corrodinggames/rts/game/units/Unit"
            )) {
                val node = ClassNode()
                javaClass.classLoader.getResourceAsStream("$target.class")!!.use { ClassReader(it).accept(node, 0) }
                assertTrue(AsmProcessor().applyAsms(target, node), target)
                assertTrue(node.methods.any { method ->
                    method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                        .any { it.owner.startsWith("io/github/rwpp/ui/") || it.owner.startsWith("io/github/rwpp/mixin/hud/") }
                }, "Handler must be injected into $target")
            }
            NativeHudVisibility.hidden = true
            assertFalse(NativeFpsHudMixin.worldFps(true))
            assertFalse(NativeFpsHudMixin.overlayFps(true))
            NativeHudVisibility.hidden = false
            assertTrue(NativeFpsHudMixin.worldFps(true))
            assertFalse(NativeFpsHudMixin.worldFps(false))
        } finally {
            AsmRegistry.clear(); NativeHudVisibility.hidden = false
        }
    }
}
