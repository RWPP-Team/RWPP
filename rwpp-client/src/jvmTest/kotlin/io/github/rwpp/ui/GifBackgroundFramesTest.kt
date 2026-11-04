package io.github.rwpp.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GifBackgroundFramesTest {
    @Test
    fun animatedFramesAreCompositedAndPublishedAsStableImages() {
        val file = Files.createTempFile("rwpp-gif-test-", ".gif").toFile()
        try {
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            ImageIO.createImageOutputStream(file).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                fun write(image: BufferedImage, left: Int, top: Int, delay: Int, disposal: String) {
                    val metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(image),
                        writer.defaultWriteParam
                    )
                    val format = "javax_imageio_gif_image_1.0"
                    val root = metadata.getAsTree(format) as IIOMetadataNode
                    val descriptor = root.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                    descriptor.setAttribute("imageLeftPosition", left.toString())
                    descriptor.setAttribute("imageTopPosition", top.toString())
                    val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                    control.setAttribute("delayTime", delay.toString())
                    control.setAttribute("disposalMethod", disposal)
                    metadata.setFromTree(format, root)
                    writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
                }

                val red = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().also { it.color = Color.RED; it.fillRect(0, 0, 2, 2); it.dispose() }
                }
                val blue = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, Color.BLUE.rgb) }
                write(red, 0, 0, 5, "doNotDispose")
                write(blue, 1, 1, 8, "restoreToPrevious")
                writer.endWriteSequence()
            }
            writer.dispose()
            assertNotNull(GifBackgroundFrames.open(file)).use { frames ->
                assertTrue(frames.animated)
                val first = frames.next()
                val second = frames.next()
                assertEquals(Color.RED.rgb, first.image.getRGB(1, 1))
                assertEquals(Color.BLUE.rgb, second.image.getRGB(1, 1))
                assertEquals(Color.RED.rgb, second.image.getRGB(0, 0))
                assertEquals(50L, first.delayMillis)
                assertEquals(80L, second.delayMillis)
                assertEquals(Color.RED.rgb, frames.next().image.getRGB(1, 1))
            }
        } finally {
            file.delete()
        }
    }
}
