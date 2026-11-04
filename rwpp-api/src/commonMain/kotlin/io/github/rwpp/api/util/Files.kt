/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun File.calculateSize(): Long {
    var size = 0L
    if (this.isFile) {
        return this.length()
    } else {
        this.listFiles()?.forEach {
            size += if (it.isFile) it.length() else it.calculateSize()
        }
    }

    return size
}

@JvmOverloads
fun File.zipFolderToByte(maxBytes: Int = Int.MAX_VALUE): ByteArray {
    require(maxBytes > 0)
    val byteOut = object : ByteArrayOutputStream(minOf(1024 * 1024, maxBytes)) {
        override fun write(value: Int) {
            check(count < maxBytes) { "Mod archive exceeds $maxBytes bytes" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length <= maxBytes - count) { "Mod archive exceeds $maxBytes bytes" }
            super.write(bytes, offset, length)
        }
    }
    ZipOutputStream(byteOut).use { zip -> zipRecursive(this, Paths.get(this.absolutePath), zip) }
    return byteOut.toByteArray()
}

private fun zipRecursive(sourceFile: File, base: Path, zip: ZipOutputStream) {
    if (sourceFile.isDirectory) {
        val fileList = sourceFile.listFiles() ?: return
        for (file in fileList) {
            zipRecursive(file, base, zip)
        }
    } else {
        val entryName = base.relativize(Paths.get(sourceFile.absolutePath)).toString()
        zip.putNextEntry(ZipEntry(entryName.replace("\\", "/")))
        sourceFile.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
