package io.github.rwpp.ui

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import io.github.rwpp.config.Settings
import io.github.rwpp.graphics.NativeObjectOpacity
import io.github.rwpp.logic.GameVisualSettings
import io.github.rwpp.mixin.building.AeroTerrainEdgeBuildingCommandMixin
import io.github.rwpp.mixin.building.AeroTerrainEdgeBuildingPreviewMixin
import io.github.rwpp.mixin.camera.CustomZoomLimitsMixin
import io.github.rwpp.mixin.game.DeathSmokeMixin
import io.github.rwpp.mixin.graphics.*
import io.github.rwpp.mixin.map.SandboxTerrainTilerScopeMixin
import io.github.rwpp.mixin.map.SandboxTerrainTilerSetTileMixin
import io.github.rwpp.mixin.music.DesktopMusicStreamAccessMixin
import io.github.rwpp.mixin.music.NativeMusicAccessMixin
import io.github.rwpp.mixin.music.NativeMusicManagerMixin
import io.github.rwpp.mixin.performance.DesktopFrameLimitMixin
import io.github.rwpp.mixin.performance.NativeFrameLimitMixin
import io.github.rwpp.mixin.replay.NativeReplayFileNameAccessMixin
import io.github.rwpp.mixin.replay.NativeReplaySeekAccessMixin
import io.github.rwpp.mixin.sandbox.*
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualAndMusicHooksTest {
    @Test
    fun keyCaptureUsesTheNativeBindingNames() {
        assertEquals("CTRL+SHIFT+T", capturedHotkey(Key.T, ctrl = true, shift = true, alt = false))
        assertEquals("NUMPADENTER", capturedHotkey(Key.NumPadEnter, ctrl = false, shift = false, alt = false))
        assertEquals("F12", capturedHotkey(Key.F12, ctrl = false, shift = false, alt = false))
        assertEquals("DPAD_LEFT", capturedHotkey(Key.DirectionLeft, ctrl = false, shift = false, alt = false))
    }

    @Test
    fun objectOpacityCopiesPaintWithoutChangingTheNativeSource() {
        val field = NativeObjectOpacity::class.java.getDeclaredField("context").apply { isAccessible = true }
        val local = field.get(null) as ThreadLocal<*>
        val state = local.get()!!
        val alpha = state.javaClass.getDeclaredField("alpha").apply { isAccessible = true }
        val previous = alpha.getFloat(state)
        try {
            val original = Paint().apply { this.alpha = 200 }
            alpha.setFloat(state, 0.5f)
            assertEquals(100, WorldCanvasTextureMixin.circle(original).alpha)
            assertEquals(100, DesktopObjectOpacityMixin.paint(original)!!.alpha)
            assertEquals(200, original.alpha)
            alpha.setFloat(state, 1f)
            assertTrue(WorldCanvasTextureMixin.circle(original) === original)
            assertTrue(DesktopObjectOpacityMixin.paint(original) === original)
        } finally {
            alpha.setFloat(state, previous)
        }
    }

    @Test
    fun fpsBoundsAndPingColorsFollowSettings() {
        val field = GameVisualSettings::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            val settings = Settings()
            field.set(null, settings)
            assertEquals(75, GameVisualSettings.frameLimit(75))
            assertEquals(16_000_000L, GameVisualSettings.frameDuration(16_000_000L))
            settings.gameFrameLimit = 120
            assertEquals(120, GameVisualSettings.frameLimit(75))
            assertEquals(1_000_000_000L / 120, GameVisualSettings.frameDuration(16_000_000L))
            settings.gameFrameLimit = 999
            assertEquals(360, GameVisualSettings.frameLimit(75))
            assertEquals(Color(0xff72dc91), pingColor(100))
            assertEquals(Color(0xffffd166), pingColor(101))
            assertEquals(Color(0xffffd166), pingColor(400))
            assertEquals(Color(0xffff6b6b), pingColor(401))
        } finally {
            field.set(null, previous)
        }
    }

    @Test
    fun musicRedirectHandlersDoNotLinkTheirTargetClassInTheirSignatures() {
        val targetName = "com.corrodinggames.rts.gameFramework.MusicController"
        for (name in listOf("available", "title")) {
            val handler = NativeMusicManagerMixin::class.java.declaredMethods.single { it.name == name }
            assertTrue(handler.parameterTypes.none { it.name == targetName }, "$name parameter links MusicController")
            assertTrue(handler.returnType.name != targetName, "$name return type links MusicController")
        }
    }

    @Test
    fun nativeHooksMatchActualGameBytecode() {
        val targets = listOf(
            Target(
                "com/corrodinggames/rts/game/RWGameEngine",
                listOf(
                    NativeReplaySeekAccessMixin::class.java,
                    NativeObjectOpacityMixin::class.java,
                    UnitAppearanceSelectionDrawScopeMixin::class.java,
                    WorldGraphicsDrawMixin::class.java,
                ),
                "NativeObjectOpacityMixin",
            ),
            Target(
                "com/corrodinggames/rts/game/units/OrderableUnit",
                listOf(DeathSmokeMixin::class.java),
                "DeathSmokeMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/gui/BattleInterface",
                listOf(CustomZoomLimitsMixin::class.java),
                "CustomZoomLimitsMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/GameThread",
                listOf(NativeFrameLimitMixin::class.java),
                "NativeFrameLimitMixin"
            ),
            Target(
                "com/corrodinggames/rts/java/SlickMainGame",
                listOf(DesktopFrameLimitMixin::class.java),
                "DesktopFrameLimitMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/MusicController",
                listOf(NativeMusicAccessMixin::class.java, NativeMusicManagerMixin::class.java),
                "NativeMusicManagerMixin"
            ),
            Target(
                "com/corrodinggames/rts/java/OpenALMusicPlayer",
                listOf(DesktopMusicStreamAccessMixin::class.java),
                null
            ),
            Target(
                "com/corrodinggames/rts/java/JavaCanvas",
                listOf(
                    DesktopPaintStateAccessMixin::class.java,
                    DesktopObjectOpacityMixin::class.java,
                    UnitAppearanceJavaCanvasMixin::class.java,
                ),
                "DesktopObjectOpacityMixin",
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/render/CanvasDirect",
                listOf(WorldCanvasTextureMixin::class.java),
                "WorldCanvasTextureMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine",
                listOf(
                    AeroTerrainEdgeBuildingPreviewMixin::class.java,
                    AeroTerrainEdgeBuildingCommandMixin::class.java,
                    NativeSandboxEditorCommandAccessMixin::class.java,
                    SandboxEditorForceBuildMixin::class.java
                ),
                "AeroTerrainEdgeBuilding"
            ),
            Target(
                "com/corrodinggames/rts/game/units/TerrainTiler",
                listOf(SandboxTerrainTilerSetTileMixin::class.java, SandboxTerrainTilerScopeMixin::class.java),
                "SandboxTerrainTiler"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/CommandController",
                listOf(NativeSandboxEditorCommandDispatchMixin::class.java),
                "NativeSandboxEditorCommandDispatchMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/ReplayEngine",
                listOf(
                    NativeReplayFileNameAccessMixin::class.java,
                    NativeSandboxReplayCommandDispatchMixin::class.java
                ),
                "NativeSandboxReplayCommandDispatchMixin"
            ),
            Target(
                "com/corrodinggames/rts/gameFramework/CommandControllerCommand",
                listOf(NativeSandboxEditorPayloadAccessMixin::class.java),
                null
            )
        )
        for (target in targets) {
            AsmRegistry.clear()
            try {
                val source = javaClass.classLoader.getResourceAsStream("${target.name}.class")!!.use { it.readBytes() }
                target.mixins.forEach { AsmRegistry.register(it) }
                val result = AsmProcessor().transform(target.name, source, javaClass.classLoader)
                val node = ClassNode().also { ClassReader(result).accept(it, 0) }
                val calls = node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
                target.expectedHandler?.let { expectedHandler ->
                    assertTrue(calls.any { it.owner.contains(expectedHandler) }, "No handler found for ${target.name}")
                }
                when (target.name) {
                    "com/corrodinggames/rts/game/RWGameEngine" -> {
                        assertTrue(node.interfaces.contains("io/github/rwpp/game/nativeaccess/NativeReplaySeekAccess"))
                        assertEquals(
                            4,
                            calls.count { it.owner.contains("NativeObjectOpacityMixin") && it.name == "draw" })
                        assertTrue(calls.any { it.owner.contains("UnitAppearanceSelectionDrawScopeMixin") && it.name == "selectionDrawScope" })
                        assertTrue(calls.any { it.owner.contains("WorldGraphicsDrawMixin") && it.name == "drawWorld" })
                        assertTrue(calls.any { it.owner.contains("NativeObjectOpacityMixin") && it.name == "back" })
                        assertTrue(calls.any { it.owner.contains("NativeObjectOpacityMixin") && it.name == "icon" })
                    }

                    "com/corrodinggames/rts/java/JavaCanvas" -> {
                        assertTrue(calls.any { it.owner.contains("DesktopObjectOpacityMixin") && it.name == "invalidate" })
                        for (name in listOf("paint", "bitmap", "text"))
                            assertTrue(
                                calls.any { it.owner.contains("DesktopObjectOpacityMixin") && it.name == name },
                                name
                            )
                        for (name in listOf("rotated", "topLeft", "image", "rect", "rectF", "software"))
                            assertTrue(
                                calls.any { it.owner.contains("UnitAppearanceJavaCanvasMixin") && it.name == name },
                                name
                            )
                    }

                    "com/corrodinggames/rts/gameFramework/MusicController" -> {
                        assertTrue(node.interfaces.contains("io/github/rwpp/game/nativeaccess/NativeMusicAccess"))
                        for (name in listOf("before", "available", "title", "play", "after")) {
                            assertTrue(
                                calls.any { it.owner.contains("NativeMusicManagerMixin") && it.name == name },
                                name
                            )
                        }
                    }

                    "com/corrodinggames/rts/java/OpenALMusicPlayer" -> {
                        assertTrue(node.interfaces.contains("io/github/rwpp/game/nativeaccess/DesktopMusicStreamAccess"))
                        for (name in listOf("rwppSoundEngine", "rwppMusic", "rwppLoopRequested")) {
                            assertTrue(node.methods.any { it.name == name }, "Missing generated accessor $name")
                        }
                    }

                    "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine" -> {
                        assertTrue(calls.any { it.owner.contains("AeroTerrainEdgeBuildingCommandMixin") })
                        assertTrue(calls.any { it.owner.contains("AeroTerrainEdgeBuildingPreviewMixin") })
                        assertTrue(node.interfaces.contains("io/github/rwpp/game/sandbox/NativeSandboxEditorCommandAccess"))
                        assertTrue(calls.any { it.owner.contains("SandboxEditorForceBuildMixin") })
                    }

                    "com/corrodinggames/rts/game/units/TerrainTiler" -> {
                        assertTrue(calls.any { it.owner.contains("SandboxTerrainTilerSetTileMixin") })
                        assertTrue(calls.any { it.owner.contains("SandboxTerrainTilerScopeMixin") })
                    }

                    "com/corrodinggames/rts/gameFramework/CommandControllerCommand" ->
                        assertTrue(node.interfaces.contains("io/github/rwpp/game/sandbox/NativeSandboxEditorPayloadAccess"))
                }
            } finally {
                AsmRegistry.clear()
            }
        }
    }

    private data class Target(val name: String, val mixins: List<Class<*>>, val expectedHandler: String?)
}
