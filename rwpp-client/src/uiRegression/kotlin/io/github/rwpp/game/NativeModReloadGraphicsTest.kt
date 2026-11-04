package io.github.rwpp.game

import com.corrodinggames.rts.java.JavaCanvas
import org.junit.Assume
import org.lwjgl.opengl.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeModReloadGraphicsTest {
    @Test
    fun nativeTextureUploadPreservesTransparentPixelsWithForeignUnpackState() {
        val nativeDir = listOfNotNull(
            System.getProperty("rwpp.lwjgl.nativePath"),
            System.getenv("RWPP_LWJGL_NATIVE_PATH"), "D:/steam/steamapps/common/Rusted Warfare"
        )
            .map(java.nio.file.Path::of).firstOrNull { java.nio.file.Files.isRegularFile(it.resolve("lwjgl64.dll")) }
        Assume.assumeTrue("Steam LWJGL native unavailable", nativeDir != null)
        val oldPath = System.getProperty("org.lwjgl.librarypath")
        System.setProperty("org.lwjgl.librarypath", nativeDir!!.toString())
        val context = Pbuffer(32, 32, PixelFormat().withAlphaBits(8), null, null, ContextAttribs(3, 0))
        var buffer = 0
        try {
            context.makeCurrent()
            val source = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
            source.setRGB(2, 2, 0xffe02a70.toInt())
            source.setRGB(1, 2, 0x803180e0.toInt())
            val encoded = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
            val data = JavaCanvas.readImageDataFromStream(ByteArrayInputStream(encoded))
            buffer = GL15.glGenBuffers()
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer)
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 4096L, GL15.GL_STREAM_DRAW)
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 16)
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 2)
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 3)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8)
            val bitmap = withSlickTextureUploadState { JavaCanvas.createBitmapFromImageData(data, null) }
            try {
                assertEquals(buffer, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING))
                assertEquals(16, GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH))
                assertEquals(2, GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS))
                assertEquals(3, GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS))
                assertEquals(8, GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT))
                val texture = bitmap.slickImage.texture
                val pixels = texture.textureData
                val stride = texture.textureWidth * 4
                fun channel(x: Int, y: Int, c: Int) = pixels[y * stride + x * 4 + c].toInt() and 255
                assertEquals(0, channel(0, 0, 3), "Transparent sprite corners must remain transparent")
                assertEquals(255, channel(2, 2, 3))
                assertEquals(0xe0, channel(2, 2, 0))
                assertEquals(128, channel(1, 2, 3), "Partially transparent pixels survive native upload")
            } finally {
                bitmap.disposeBitmap()
            }
            assertFailsWith<IllegalStateException> { withSlickTextureUploadState { error("reload failed") } }
            assertEquals(buffer, GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING))
            assertEquals(16, GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH))
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
            if (buffer != 0) GL15.glDeleteBuffers(buffer)
            context.destroy()
            if (oldPath == null) System.clearProperty("org.lwjgl.librarypath") else System.setProperty(
                "org.lwjgl.librarypath",
                oldPath
            )
        }
    }
}
