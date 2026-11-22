/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.ui

import io.github.rwml.core.ExtensionDescriptor
import io.github.rwpp.game.map.MapPreviewRaster
import io.github.rwpp.game.map.decodeMapPreviewImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

private const val maxIconBytes = 1024 * 1024
private const val maxIconSide = 512

internal fun readExtensionIcon(descriptor: ExtensionDescriptor, loader: ClassLoader): MapPreviewRaster? {
    val path = descriptor.icon().removePrefix("/")
    if (path.isBlank() || ':' in path || '\\' in path || path.split('/').any { it == "" || it == "." || it == ".." }) return null
    val source = descriptor.source()
    val bytes = if (source == null) {
        loader.getResourceAsStream(path)?.use { readIconBytes(it) }
    } else {
        ZipFile(source.toFile()).use { zip ->
            val entry = zip.getEntry(path) ?: return null
            if (entry.isDirectory || entry.size > maxIconBytes) return null
            zip.getInputStream(entry).use { readIconBytes(it) }
        }
    }
    return bytes?.let { decodeMapPreviewImage(it, maxSide = maxIconSide) }
}

private fun readIconBytes(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (count > maxIconBytes - output.size()) return null
        output.write(buffer, 0, count)
    }
}
