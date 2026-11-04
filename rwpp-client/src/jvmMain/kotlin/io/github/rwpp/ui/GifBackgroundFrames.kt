/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import org.w3c.dom.Node
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

/** Decodes one composite frame at a time; GIFs can use partial frames and disposal instructions. */
internal class GifBackgroundFrames private constructor(
    private val reader: ImageReader,
    private val input: ImageInputStream,
    width: Int,
    height: Int,
    private val count: Int
) : AutoCloseable {
    data class Frame(val image: BufferedImage, val delayMillis: Long)

    val animated: Boolean get() = count > 1
    private var canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    private var index = 0
    private var previousDisposal = "none"
    private var previousX = 0
    private var previousY = 0
    private var previousWidth = 0
    private var previousHeight = 0
    private var restore: BufferedImage? = null

    fun next(): Frame {
        if (index == 0) {
            canvas = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
            previousDisposal = "none"
            restore = null
        } else when (previousDisposal) {
            "restoreToBackgroundColor" -> canvas.createGraphics().useGraphics {
                it.composite = AlphaComposite.Clear
                it.fillRect(previousX, previousY, previousWidth, previousHeight)
            }

            "restoreToPrevious" -> restore?.let { canvas = it }
        }

        val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
        val descriptor = metadata.child("ImageDescriptor")
        val control = metadata.child("GraphicControlExtension")
        val x = descriptor.intAttribute("imageLeftPosition")
        val y = descriptor.intAttribute("imageTopPosition")
        val frame = reader.read(index)
        val disposal = control.attribute("disposalMethod") ?: "none"
        restore = if (disposal == "restoreToPrevious") canvas.copyImage() else null
        canvas.createGraphics().useGraphics { it.drawImage(frame, x, y, null) }
        previousDisposal = disposal
        previousX = x
        previousY = y
        previousWidth = frame.width
        previousHeight = frame.height
        index = (index + 1) % count
        return Frame(canvas.copyImage(), (control.intAttribute("delayTime") * 10L).coerceAtLeast(20L))
    }

    override fun close() {
        try {
            reader.dispose()
        } finally {
            input.close()
        }
    }

    companion object {
        fun open(file: File): GifBackgroundFrames? {
            val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull() ?: return null
            val input = ImageIO.createImageInputStream(file) ?: run { reader.dispose(); return null }
            try {
                reader.input = input
                val count = reader.getNumImages(true)
                check(count > 0) { "GIF has no frames" }
                val root = reader.streamMetadata.getAsTree("javax_imageio_gif_stream_1.0")
                val screen = root.child("LogicalScreenDescriptor")
                val width = screen.intAttribute("logicalScreenWidth")
                val height = screen.intAttribute("logicalScreenHeight")
                check(width > 0 && height > 0 && width.toLong() * height <= 64_000_000L) { "Invalid GIF dimensions" }
                return GifBackgroundFrames(reader, input, width, height, count)
            } catch (failure: Throwable) {
                reader.dispose()
                input.close()
                throw failure
            }
        }
    }
}

private fun Node.child(name: String): Node {
    var child = firstChild
    while (child != null) {
        if (child.nodeName == name) return child
        child = child.nextSibling
    }
    error("Missing GIF metadata: $name")
}

private fun Node.attribute(name: String): String? = attributes?.getNamedItem(name)?.nodeValue
private fun Node.intAttribute(name: String): Int = attribute(name)?.toIntOrNull() ?: 0
private fun BufferedImage.copyImage(): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
        copy.createGraphics().useGraphics { it.drawImage(this, 0, 0, null) }
    }

private inline fun java.awt.Graphics2D.useGraphics(block: (java.awt.Graphics2D) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
