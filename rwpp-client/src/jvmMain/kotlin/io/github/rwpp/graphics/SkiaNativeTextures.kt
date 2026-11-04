/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.TeamColorMode
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.BitmapOrTextureLazyColoring
import com.corrodinggames.rts.java.JavaBitmap
import org.jetbrains.skia.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun skiaImageFromArgb(pixels: IntArray, width: Int, height: Int): Image {
    val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    pixels.forEach(bytes::putInt)
    return Bitmap().use { bitmap ->
        check(
            bitmap.installPixels(
                ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL),
                bytes.array(),
                width * 4
            )
        )
        Image.makeFromBitmap(bitmap)
    }
}

/** Asset bytes and CPU color variants only. Never invokes Slick texture download or a GL shader. */
internal object SkiaNativeTextures {
    private data class Entry(val image: Image, val pixels: IntArray)
    private data class EagerTeamColorSource(
        val source: BitmapOrTexture,
        val mode: TeamColorMode,
        val teamIndex: Int,
    )

    private val cache = LinkedHashMap<String, Entry>()

    // Native CPU coloring discards sourceImage and uploads a GPU-only clone. Keep only
    // its asset provenance (weak keys), so Skia can recreate the variant without GL.
    private val colorSources = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<BitmapOrTextureLazyColoring, BitmapOrTexture>()
    )

    // Vanilla desktop preferences commonly select eager CPU team coloring. The resulting
    // JavaBitmap clones have no filepath and dispose their CPU pixels, so keep their source
    // texture and the exact coloring parameters as long as the native variant is alive.
    private val eagerColorSources = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<BitmapOrTexture, EagerTeamColorSource>()
    )

    fun retainColorSource(texture: BitmapOrTextureLazyColoring) {
        (texture.field("sourceImage") as? BitmapOrTexture)?.let { colorSources[texture] = it }
    }

    fun retainEagerTeamColorSources(
        source: BitmapOrTexture, mode: TeamColorMode,
        variants: Array<*>
    ) {
        variants.forEachIndexed { index, variant ->
            if (index > 0 && variant is BitmapOrTexture && variant !== source && variant !is BitmapOrTextureLazyColoring) {
                eagerColorSources[variant] = EagerTeamColorSource(source, mode, index)
            }
        }
    }

    private val path = JavaBitmap::class.java.getDeclaredField("filepath").apply { isAccessible = true }
    private val buffer = JavaBitmap::class.java.getDeclaredField("imageByteBuffer").apply { isAccessible = true }
    private val slickImageData = JavaBitmap::class.java.getDeclaredField("slickImageData").apply { isAccessible = true }
    private fun BitmapOrTextureLazyColoring.field(name: String): Any? =
        BitmapOrTextureLazyColoring::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

    fun image(texture: BitmapOrTexture): Image = entry(texture).image
    fun pixels(texture: BitmapOrTexture): IntArray = entry(texture).pixels
    private fun entry(texture: BitmapOrTexture): Entry {
        val key = "${texture.id}:${texture.version}"
        cache[key]?.let { return it }
        val result = if (texture is BitmapOrTextureLazyColoring) {
            val source = (texture.field("sourceImage") as? BitmapOrTexture) ?: colorSources[texture]
            if (source == null) entry(texture.field("coloredImage") as BitmapOrTexture)
            else colorVariant(
                source, texture.field("teamColorMode") as TeamColorMode,
                texture.field("teamColor") as Int, texture.field("teamId") as Int
            )
        } else eagerColorSources[texture]?.let { sourceInfo ->
            colorVariant(
                sourceInfo.source, sourceInfo.mode,
                Team.lookupTeamColor(sourceInfo.teamIndex), sourceInfo.teamIndex
            )
        } ?: run {
            val asset = (texture as? JavaBitmap)?.let { path.get(it) as? String }
            val image = asset?.let(::assetImage)
            if (image != null) {
                val data = Bitmap().use { bitmap ->
                    bitmap.allocPixels(image.imageInfo)
                    check(image.readPixels(bitmap))
                    IntArray(image.width * image.height) { bitmap.getColor(it % image.width, it / image.width) }
                }
                Entry(image, data)
            } else {
                generatedShadow(texture)?.let { result ->
                    cache[key] = result
                    return result
                }
                // Generated native textures can retain CPU pixels. Do not silently read back GL.
                if (texture.pixelData != null) {
                    val data = texture.pixelData.copyOf()
                    return Entry(skiaImageFromArgb(data, texture.width, texture.height), data).also { cache[key] = it }
                }
                if (texture is JavaBitmap) check(buffer.get(texture) != null && slickImageData.get(texture) != null) {
                    // A stale ByteBuffer alone is not enough: JavaBitmap.getPixel dereferences
                    // ImageData before reading it. Do not attempt Slick texture readback here.
                    "No asset or usable Slick CPU pixels for ${texture.name}"
                } else texture.ensurePixelData()
                val data = IntArray(texture.width * texture.height) {
                    texture.getPixel(
                        it % texture.width,
                        it / texture.width
                    )
                }
                Entry(skiaImageFromArgb(data, texture.width, texture.height), data)
            }
        }
        // Entries returned for already-colored wrappers share the native image; leave its owner cached.
        cache[key] = result
        while (cache.size > 1 && (cache.size > 256 || cache.values.sumOf { it.pixels.size.toLong() * 8 } > 64L * 1024 * 1024))
            cache.remove(cache.keys.first())
        return result
    }

    private fun assetImage(file: String): Image? {
        val normalized = file.replace('\\', '/')
        val bytes = if (File(normalized).isFile) File(normalized).readBytes()
        else FileLoader.openByPath(normalized)?.use { it.readBytes() }
        return bytes?.let { Image.makeFromEncoded(it) }
    }

    /** Unit.createShadowImage retains its source path in the name but drops CPU pixels after
     * upload. Leg/pod shadows are drawn even with extra shadows disabled. Rebuild the native
     * black RGB + source alpha, without Slick readback or dropping the entire unit preview.
     */
    private fun generatedShadow(texture: BitmapOrTexture): Entry? {
        val name = texture.name ?: return null
        if (!name.startsWith("shadow:")) return null
        val source = assetImage(name.removePrefix("shadow:")) ?: return null
        return source.use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                val data = IntArray(texture.width * texture.height) { i ->
                    bitmap.getColor(
                        (i % texture.width).coerceAtMost(image.width - 1),
                        (i / texture.width).coerceAtMost(image.height - 1)
                    ) and 0xff000000.toInt()
                }
                Entry(skiaImageFromArgb(data, texture.width, texture.height), data)
            }
        }
    }

    private fun cpuBitmap(data: IntArray, w: Int, h: Int) = BitmapOrTexture().apply {
        width = w; height = h; pixelData = data; computeHalfSizes()
    }

    private fun colorVariant(source: BitmapOrTexture, mode: TeamColorMode, color: Int, teamIndex: Int): Entry {
        val sourcePixels = pixels(source)
        val base = cpuBitmap(sourcePixels, source.width, source.height)
        val colored = cpuBitmap(sourcePixels.copyOf(), source.width, source.height)
        when (mode) {
            TeamColorMode.hueAdd -> Team.applyHueAddColoring(base, arrayOf(colored), intArrayOf(color))
            TeamColorMode.hueShift -> Team.applyHueShiftColoring(
                base,
                arrayOf(colored),
                intArrayOf(color),
                intArrayOf(teamIndex)
            )

            else -> Team.applyHueNewColoring(base, arrayOf(colored), intArrayOf(color))
        }
        val pixels = requireNotNull(colored.pixelData)
        return Entry(skiaImageFromArgb(pixels, source.width, source.height), pixels)
    }
}
