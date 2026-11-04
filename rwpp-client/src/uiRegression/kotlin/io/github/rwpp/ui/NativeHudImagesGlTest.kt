package io.github.rwpp.ui

import android.graphics.Rect
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.java.JavaBitmap
import com.corrodinggames.rts.java.JavaCanvas
import io.github.rwpp.graphics.withNativeHudCanvas
import org.junit.Assume
import org.lwjgl.opengl.ContextAttribs
import org.lwjgl.opengl.Pbuffer
import org.lwjgl.opengl.PixelFormat
import org.newdawn.slick.Graphics
import kotlin.test.*

/** Real native GPU pixels, not the retired raster proxy or original-asset reconstruction. */
class NativeHudImagesGlTest {
    @Test
    fun gpuOnlyIconsKeepOrientationAndSurviveSourceDisposalAndNextNativeDraw() {
        val nativeDir = listOfNotNull(
            System.getProperty("rwpp.lwjgl.nativePath"),
            System.getenv("RWPP_LWJGL_NATIVE_PATH"), "D:/steam/steamapps/common/Rusted Warfare"
        )
            .map(java.nio.file.Path::of).firstOrNull { java.nio.file.Files.isRegularFile(it.resolve("lwjgl64.dll")) }
        Assume.assumeTrue("Steam LWJGL native unavailable", nativeDir != null)
        val oldPath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val context = Pbuffer(128, 128, PixelFormat().withAlphaBits(8), null, null, ContextAttribs(3, 0))
        val singleton = GameEngine::class.java.getDeclaredField("al").apply { isAccessible = true }
        val previous = singleton.get(null)
        val nativeFields = listOf("currentCanvas", "lastBoundGraphics", "currentShader").map {
            JavaCanvas::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val nativeValues = nativeFields.map { it.get(null) }
        var renderer: JavaCanvas? = null
        var texture: com.corrodinggames.rts.gameFramework.render.BitmapOrTexture? = null
        try {
            context.makeCurrent()
            JavaCanvas.currentShader = null; JavaCanvas.lastBoundGraphics = null
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
                .get(null) as sun.misc.Unsafe
            val engine = unsafe.allocateInstance(RWGameEngine::class.java) as RWGameEngine
            GameEngine::class.java.getField("renderLock").apply { isAccessible = true }.set(engine, Any())
            renderer = JavaCanvas().apply { width = 128; height = 128; slickGraphics = Graphics(128, 128) }
            engine.graphics = renderer
            singleton.set(null, engine)
            texture = renderer.createImage(4, 4, true)
            val source = renderer.createBitmapCanvas(texture)
            source.drawColor(0xff2040e0.toInt())
            source.drawRect(Rect(0, 0, 4, 2), android.graphics.Paint().apply { color = 0xffe04020.toInt() })
            source.flushBitmap()
            source.PENDING_noop_q()
            renderer.makeCurrent()
            texture.disposePixelData()
            // This generated texture has no asset path, CPU buffer or retained team-color provenance.
            assertNull(texture.pixelData)
            assertTrue(texture is JavaBitmap)
            val image = synchronized(engine.renderLock) {
                withNativeHudCanvas { canvas ->
                    canvas.drawImage(texture, Rect(0, 0, 4, 4), android.graphics.RectF(0f, 0f, 96f, 96f), null)
                }
            }
            val pixels = IntArray(96 * 96)
            image.readPixels(pixels)
            assertEquals(0xffe04020.toInt(), pixels[20 * 96 + 48], "top must remain red after GPU readback")
            assertEquals(0xff2040e0.toInt(), pixels[76 * 96 + 48], "bottom must remain blue after GPU readback")
            assertSame(renderer, engine.graphics)
            val failure = assertFailsWith<IllegalStateException> {
                synchronized(engine.renderLock) { withNativeHudCanvas { error("native draw failure") } }
            }
            assertEquals("native draw failure", failure.message)
            assertSame(renderer, engine.graphics, "even a failed preview must restore the game's renderer")
            val next = synchronized(engine.renderLock) { withNativeHudCanvas { it.drawColor(0xff10c040.toInt()) } }
            val nextPixels = IntArray(96 * 96); next.readPixels(nextPixels)
            assertTrue(nextPixels.all { it == 0xff10c040.toInt() })
            texture.disposeBitmap(); texture = null
            image.readPixels(pixels)
            assertEquals(
                0xffe04020.toInt(),
                pixels[20 * 96 + 48],
                "Compose owns its pixels after native target/source release"
            )
        } finally {
            texture?.disposeBitmap()
            singleton.set(null, previous)
            nativeFields.zip(nativeValues).forEach { (field, value) -> field.set(null, value) }
            context.destroy()
            if (oldPath == null) System.clearProperty("org.lwjgl.librarypath") else System.setProperty(
                "org.lwjgl.librarypath",
                oldPath
            )
        }
    }
}
