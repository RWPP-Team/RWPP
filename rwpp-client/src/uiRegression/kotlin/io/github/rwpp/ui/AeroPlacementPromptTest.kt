package io.github.rwpp.ui

import com.corrodinggames.rts.gameFramework.gui.PromptResult
import io.github.rwpp.graphics.AeroPlacementPromptIcon
import io.github.rwpp.graphics.AeroPlacementPromptPainter
import io.github.rwpp.graphics.aeroPlacementPromptIcon
import io.github.rwpp.mixin.hud.AeroPlacementPromptMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.AsmScanner
import kim.der.asm.transformer.AsmProcessor
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroPlacementPromptTest {
    @Test
    fun nativePromptRolesMapToClearAeroActions() {
        assertEquals(AeroPlacementPromptIcon.CONFIRM, aeroPlacementPromptIcon(PromptResult.yes))
        assertEquals(AeroPlacementPromptIcon.REPEAT, aeroPlacementPromptIcon(PromptResult.more))
        assertEquals(AeroPlacementPromptIcon.CANCEL, aeroPlacementPromptIcon(PromptResult.no))
    }

    @Test
    fun wrapsAllThreeNativePromptArtCallsAndKeepsNativeHitTesting() {
        AsmRegistry.clear()
        val archive = Files.createTempFile("rwpp-aero-prompt-mixins", ".jar")
        try {
            val mixinPath = "${AeroPlacementPromptMixin::class.java.name.replace('.', '/')}.class"
            JarOutputStream(Files.newOutputStream(archive)).use { jar ->
                jar.putNextEntry(JarEntry(mixinPath))
                javaClass.classLoader.getResourceAsStream(mixinPath)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
            // RWML calls MixinBridge.registerArchive before instantiating the extension. This is
            // the same archive scanner path, not a direct AsmRegistry.register shortcut.
            val scan = AsmScanner.scanJarWithResult(archive.toFile(), "")
            assertTrue(
                AeroPlacementPromptMixin::class.java.name in scan.registeredClasses,
                "archive scanning should discover AeroPlacementPromptMixin; registered=${scan.registeredClasses}",
            )
            assertTrue(scan.failures.isEmpty(), "archive mixin scan must be clean: ${scan.failures}")

            val target = "com/corrodinggames/rts/gameFramework/gui/InterfaceEngine"
            val node = ClassNode()
            javaClass.classLoader.getResourceAsStream("$target.class")!!.use {
                ClassReader(it).accept(node, 0)
            }

            assertTrue(AsmProcessor().applyAsms(target, node))
            val method = node.methods.single {
                it.name == "handlePromptButton" &&
                        it.desc == "(Lcom/corrodinggames/rts/gameFramework/gui/PromptResult;Z)Z"
            }
            val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertEquals(3, calls.count {
                it.owner == "io/github/rwpp/mixin/hud/AeroPlacementPromptMixin" &&
                        it.name == "renderAeroPromptButton"
            }, "confirm, repeat-placement and cancel artwork all use the Aero painter")
            assertTrue(calls.any { it.name == "updateUiHover" }, "native hover and button bounds remain in charge")
            assertTrue(
                calls.any { it.owner == "android/graphics/Rect" && it.name == "contains" },
                "native pointer hit testing stays unchanged"
            )
        } finally {
            AsmRegistry.clear()
            Files.deleteIfExists(archive)
        }
    }

    @Test
    fun skiaPreviewKeepsAllThreeIconsInsideTheirNativeButtonBounds() {
        val scale = 0.6f
        val size = (100f * scale).toInt()
        val top = 8
        val lefts = listOf(8, 80, 152)
        val icons = listOf(
            AeroPlacementPromptIcon.CONFIRM, AeroPlacementPromptIcon.REPEAT,
            AeroPlacementPromptIcon.CANCEL
        )
        val width = 220
        val height = 76

        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0)
            val graphics = SkiaNativeGraphics(surface.canvas, width, height).graphics
            icons.forEachIndexed { index, icon ->
                AeroPlacementPromptPainter.draw(graphics, icon, lefts[index].toFloat(), top.toFloat(), scale)
            }

            val image = surface.makeImageSnapshot()
            try {
                val png = image.encodeToData()!!
                try {
                    Files.createDirectories(java.nio.file.Path.of("build/ui-regression"))
                    Files.write(java.nio.file.Path.of("build/ui-regression/aero-placement-prompts.png"), png.bytes)
                } finally {
                    png.close()
                }

                Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo)
                    assertTrue(image.readPixels(bitmap), "Skia should copy the prompt preview into the pixel buffer")
                    icons.forEachIndexed { index, icon ->
                        val left = lefts[index]
                        var brightPixels = 0
                        for (y in top - 6 until top + size + 6) {
                            for (x in left - 6 until left + size + 6) {
                                val color = bitmap.getColor(x, y)
                                val insideNativeBounds = x in left until left + size && y in top until top + size
                                assertTrue(
                                    insideNativeBounds || color ushr 24 == 0,
                                    "$icon drawing escaped its native ${size}px button at ($x,$y): 0x${
                                        color.toUInt().toString(16)
                                    }"
                                )
                                if (insideNativeBounds && color ushr 24 > 0 &&
                                    maxOf(color shr 16 and 255, color shr 8 and 255, color and 255) > 180
                                ) {
                                    brightPixels++
                                }
                            }
                        }
                        assertTrue(brightPixels > 8, "$icon should show a visible Aero glyph in the preview")
                    }
                }
            } finally {
                image.close()
            }
        }
    }
}
