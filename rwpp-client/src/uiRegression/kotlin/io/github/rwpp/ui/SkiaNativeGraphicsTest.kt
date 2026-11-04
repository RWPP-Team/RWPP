package io.github.rwpp.ui

import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.TeamColorMode
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.SpecialActionBuild
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.BitmapOrTextureLazyColoring
import com.corrodinggames.rts.java.JavaBitmap
import io.github.rwpp.graphics.SkiaNativeTextures
import io.github.rwpp.graphics.skiaImageFromArgb
import io.github.rwpp.mixin.graphics.NativeEagerTeamColorSourceMixin
import kim.der.asm.AsmRegistry
import kim.der.asm.transformer.AsmProcessor
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkiaNativeGraphicsTest {
    @Test
    fun coloredPreviewSurvivesNativeDiscardingItsSourceAndCpuClone() {
        val base = texture(intArrayOf(0xff00ff00.toInt(), 0xff999999.toInt()), 2, 1)
        val wrapper = BitmapOrTextureLazyColoring(base, 0xffff0000.toInt(), TeamColorMode.pureGreen, 1)
        SkiaNativeTextures.retainColorSource(wrapper)
        BitmapOrTextureLazyColoring::class.java.getDeclaredField("sourceImage").apply { isAccessible = true }
            .set(wrapper, null)
        BitmapOrTextureLazyColoring::class.java.getDeclaredField("coloredImage").apply { isAccessible = true }
            .set(wrapper, JavaBitmap())
        val pixels = SkiaNativeTextures.pixels(wrapper)
        assertEquals(0xfffe0000.toInt(), pixels[0]) // Native pureGreen uses an 8-bit shift.
        assertEquals(0xff999999.toInt(), pixels[1])
    }

    @Test
    fun eagerTeamColorMixinMatchesTheVanillaSetTeamImagesDescriptor() {
        val target = "com/corrodinggames/rts/gameFramework/render/BitmapOrTexture"
        val classLoader = javaClass.classLoader
        val source = requireNotNull(classLoader.getResourceAsStream("$target.class")) {
            "native BitmapOrTexture must be present in the UI regression classpath"
        }.use { it.readBytes() }
        AsmRegistry.clear()
        try {
            AsmRegistry.register(NativeEagerTeamColorSourceMixin::class.java)
            val transformed = AsmProcessor().transform(target, source, classLoader)
            val node = ClassNode()
            ClassReader(transformed).accept(node, ClassReader.EXPAND_FRAMES)
            val setTeamImages = node.methods.single {
                it.name == "setTeamImages" &&
                        it.desc == "(Lcom/corrodinggames/rts/game/TeamColorMode;[Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V"
            }
            assertTrue(setTeamImages.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
                it.owner == NativeEagerTeamColorSourceMixin::class.java.name.replace('.', '/') &&
                        it.name == "retainEagerSources"
            }, "the exact vanilla descriptor must invoke the source-retention hook")
        } finally {
            AsmRegistry.clear()
        }
    }

    @Test
    fun gpuOnlyGeneratedPartShadowsRecoverNativeAlphaFromTheirSourceAsset() {
        val file = Files.createTempFile("rwpp-part-shadow", ".png")
        try {
            val source = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val colors = intArrayOf(0x80ff8040.toInt(), 0x00000000, -1, 0x4000ff00)
            source.setRGB(0, 0, 2, 2, colors, 0, 2)
            javax.imageio.ImageIO.write(source, "png", file.toFile())
            val shadow = JavaBitmap().apply { width = 2; height = 2; name = "shadow:$file"; computeHalfSizes() }
            assertContentEquals(
                colors.map { it and 0xff000000.toInt() }.toIntArray(),
                SkiaNativeTextures.pixels(shadow)
            )
            Surface.makeRasterN32Premul(8, 8).use { surface ->
                surface.canvas.clear(0)
                SkiaNativeGraphics(surface.canvas, 8, 8).graphics.drawImageRotated(shadow, 4f, 4f, 0f, null)
                assertTrue(
                    (0..7).any { y -> (0..7).any { x -> pixel(surface, x, y) ushr 24 > 0 } },
                    "Generated limb shadow must no longer abort native preview drawing"
                )
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun nativeBitmapPaintPreservesTintAndAlpha() {
        val body = texture(intArrayOf(-1), 1, 1)
        val paint = android.graphics.Paint().apply { color = 0x80ff4000.toInt() }
        Surface.makeRasterN32Premul(4, 4).use { surface ->
            surface.canvas.clear(0)
            SkiaNativeGraphics(surface.canvas, 4, 4).graphics.drawImage(
                body,
                Rect(0, 0, 1, 1),
                RectF(0f, 0f, 4f, 4f),
                paint
            )
            val actual = pixel(surface, 1, 1)
            assertEquals(128, actual ushr 24)
            assertTrue((actual shr 16 and 255) > 240)
            assertTrue((actual shr 8 and 255) in 60..68)
        }
    }

    private fun texture(pixels: IntArray, w: Int, h: Int) = BitmapOrTexture().apply {
        width = w; height = h; pixelData = pixels; computeHalfSizes()
    }

    private fun pixel(surface: Surface, x: Int, y: Int): Int = surface.makeImageSnapshot().use { image ->
        Bitmap().use { bitmap -> bitmap.allocPixels(image.imageInfo); image.readPixels(bitmap); bitmap.getColor(x, y) }
    }

    @Test
    fun nativeSpriteCropScaleAndTurretLayerDrawWithoutAnOpenGlContext() {
        val body = texture(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1), 2, 2)
        val turret = texture(intArrayOf(0xffffff00.toInt()), 1, 1)
        Surface.makeRasterN32Premul(32, 32).use { surface ->
            surface.canvas.clear(0)
            val g = SkiaNativeGraphics(surface.canvas, 32, 32).graphics
            g.drawImage(body, Rect(0, 0, 1, 1), RectF(4f, 4f, 20f, 20f), null)
            g.save(); g.translate(8f, 8f); g.scale(4f, 4f)
            g.drawImageTopLeft(turret, 0f, 0f, null); g.restore()
            assertEquals(0xffff0000.toInt(), pixel(surface, 6, 6))
            assertEquals(0xffffff00.toInt(), pixel(surface, 9, 9))
            assertEquals(0, pixel(surface, 0, 0))
            Files.createDirectories(java.nio.file.Path.of("build/ui-regression"))
            surface.makeImageSnapshot().use {
                it.encodeToData()!!.use { data ->
                    Files.write(java.nio.file.Path.of("build/ui-regression/skia-native-preview.png"), data.bytes)
                }
            }
        }
    }

    @Test
    fun skiaDecodesNativeAssetsEvenWhenAllSlickCpuAndGpuReadsAreUnavailable() {
        val file = Files.createTempFile("rwpp-skia-texture", ".png")
        try {
            skiaImageFromArgb(intArrayOf(0xff30a0e0.toInt()), 1, 1).use { image ->
                image.encodeToData()!!.use { Files.write(file, it.bytes) }
            }
            val native = object : JavaBitmap() {
                override fun getPixel(x: Int, y: Int): Int = error("Slick readback is forbidden")
                override fun ensureImageData() = error("OpenGL context is unavailable")
            }.apply { width = 1; height = 1; computeHalfSizes() }
            JavaBitmap::class.java.getDeclaredField("filepath").apply { isAccessible = true }
                .set(native, file.toString())
            assertContentEquals(intArrayOf(0xff30a0e0.toInt()), SkiaNativeTextures.pixels(native))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private class IconBuildAction(
        type: UnitTypeInterface, private val overrideIcon: BitmapOrTexture,
        actionId: String
    ) : SpecialActionBuild(type) {
        init {
            setActionIdFromString(actionId)
        }

        override fun getIcon(): BitmapOrTexture = overrideIcon
        override fun compareTo(other: Any?): Int = 0
    }

    private fun unsafeAllocate(type: Class<*>): Any = sun.misc.Unsafe::class.java
        .getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        .let { (it as sun.misc.Unsafe).allocateInstance(type) }

    @Test
    fun nativeTeamColorShaderIsReplacedByTheNativeCpuColorAlgorithm() {
        // Native Team initializes dummy units whose constructor reads the engine clock. Supply
        // only that clock without starting a game loop, window, asset loader, or GL context.
        val engineField = com.corrodinggames.rts.gameFramework.GameEngine::class.java
            .getDeclaredField("al").apply { isAccessible = true }
        val previous = engineField.get(null)
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        if (previous == null) engineField.set(
            null,
            unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java)
        )
        try {
            val source = texture(intArrayOf(0xff00ff00.toInt(), 0xff606060.toInt()), 2, 1)
            val team = BitmapOrTextureLazyColoring(source, 0xffff0000.toInt(), TeamColorMode.pureGreen, 1)
            val colors = SkiaNativeTextures.pixels(team)
            assertEquals(0xfffe0000.toInt(), colors[0])
            assertEquals(0xff606060.toInt(), colors[1])
        } finally {
            engineField.set(null, previous)
        }
    }
}
