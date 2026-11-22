package io.github.rwpp.ui

import io.github.rwml.core.ExtensionDescriptorReader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtensionIconsTest {
    @Test
    fun readsTheDeclaredArchiveImageAndPreservesItsPixels() {
        withArchive("/assets/icon.png", png(2, 2)) { icon ->
            assertEquals(2, assertNotNull(icon).width)
            assertEquals(2, icon.height)
            assertEquals(0xff4678cc.toInt(), icon.pixels.first())
        }
    }

    @Test
    fun fallsBackWhenTheArchiveEntryIsMissingOrExceedsTheByteLimit() {
        withArchive("missing.png", png(1, 1)) { assertNull(it) }
        withArchive("assets/icon.png", ByteArray(1024 * 1024 + 1)) { assertNull(it) }
    }

    @Test
    fun rejectsOversizedImageDimensionsBeforeDecoding() {
        assertFailsWith<IllegalArgumentException> {
            withArchive("assets/icon.png", png(513, 1)) {}
        }
    }

    private fun withArchive(
        declaredIcon: String,
        bytes: ByteArray,
        verify: (io.github.rwpp.game.map.MapPreviewRaster?) -> Unit,
    ) {
        val path = Files.createTempFile("rwpp-extension-icon", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/extension.properties"))
                zip.write("id=test.extension.icon\nmainClass=test.Extension\nicon=$declaredIcon\n".encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/icon.png"))
                zip.write(bytes)
                zip.closeEntry()
            }
            verify(readExtensionIcon(ExtensionDescriptorReader.read(path), javaClass.classLoader))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xff4678cc.toInt())
        return ByteArrayOutputStream().use {
            check(ImageIO.write(image, "png", it))
            it.toByteArray()
        }
    }
}
