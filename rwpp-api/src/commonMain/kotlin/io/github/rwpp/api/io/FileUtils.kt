/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.io

import java.io.File
import java.util.zip.ZipFile

fun File.unzipTo(targetFile: File, from: String = "") {
    val zipFile = ZipFile(this)
    for (entry in zipFile.entries()) {
        if (entry.name.startsWith(from)) {
            if (entry.isDirectory) {
                File(targetFile, entry.name).mkdirs()
            } else {
                val file = File(targetFile, entry.name)
                if (!file.exists()) {
                    if (!file.parentFile.exists()) file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.writeBytes(zipFile.getInputStream(entry).readBytes())
            }
        }
    }
}