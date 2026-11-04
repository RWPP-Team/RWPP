/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal actual fun decodeMapPreviewImage(bytes: ByteArray): MapPreviewRaster {
    val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        ?: error("Tileset image could not be opened")
    input.use {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: error("Unsupported tileset image format")
        reader.input = input
        try {
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 8_000_000L) {
                "Tileset image dimensions exceed the preview limit"
            }
            val image = reader.read(0)
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            return MapPreviewRaster(width, height, pixels)
        } finally {
            reader.dispose()
        }
    }
}

internal actual fun encodeMapPreviewPng(image: MapPreviewRaster): ByteArray {
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(buffered, "png", output)) { "Preview PNG encoding failed" }
        output.toByteArray()
    }
}
