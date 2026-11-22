/*
 * Copyright 2023-2026 RWPP contributors
 * Use of this source code is governed by the RWPP-Team Shield license.
 */

package io.github.rwpp.net

import com.corrodinggames.rts.gameFramework.files.FileLoader
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.net.Net
import io.github.rwpp.game.importRwmod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

internal suspend fun Net.downloadBbsResource(
    info: NetResourceInfo,
    type: ResourceType,
    onProgress: (Float) -> Unit,
) {
    val url = checkNotNull(info.downloadUrl)
    val extension = if (type == ResourceType.Mod) "rwmod" else "tmx"
    val progressContext = coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default
    val staging = withContext(Dispatchers.IO) { Files.createTempDirectory("rwpp-resource-").toFile() }
    try {
        val file = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newBuilder().followSslRedirects(false).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val name = bbsResourceFileName(response.request.url.pathSegments.last(), info.title, extension)
                val target = File(staging, name)
                val length = response.body.contentLength()
                var written = 0L
                var lastProgress = -1
                response.body.byteStream().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            if (length > 0) {
                                val percent = (written.toDouble() * 100 / length).toInt().coerceIn(0, 99)
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    withContext(progressContext) { onProgress(percent / 100f) }
                                }
                            }
                        }
                    }
                }
                if (written == 0L || (length >= 0L && length != written)) throw IOException("Incomplete download")
                target
            }
        }

        when (type) {
            ResourceType.Mod -> {
                withContext(Dispatchers.IO) {
                    ZipFile(file).use { if (it.size() == 0) throw IOException(readI18n("rwpp.browser.invalidResource")) }
                }
                importRwmod(file)
            }
            ResourceType.Map -> withContext(Dispatchers.IO) { installBbsMap(file) }
        }
        onProgress(1f)
    } finally {
        staging.listFiles()?.forEach(File::delete)
        staging.delete()
    }
}

private val unsafeResourceFileCharacters = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
private val mapHeader = Regex("""<map(?=\s|>)""")

internal fun bbsResourceFileName(downloadName: String, title: String, extension: String): String {
    fun safeStem(candidate: String): String {
        val stem = if (candidate.endsWith(".$extension", ignoreCase = true)) {
            candidate.dropLast(extension.length + 1)
        } else candidate
        return stem.replace(unsafeResourceFileCharacters, "_").take(100).trim(' ', '.')
    }

    val candidate = downloadName.takeIf { it.endsWith(".$extension", ignoreCase = true) }?.let(::safeStem)
    return (candidate?.takeIf(String::isNotBlank) ?: safeStem(title).ifBlank { "resource" }) + ".$extension"
}

private fun installBbsMap(file: File) {
    val buffer = ByteArray(8192)
    val count = file.inputStream().use { it.read(buffer) }
    if (count < 0 || !mapHeader.containsMatchIn(String(buffer, 0, count, Charsets.UTF_8))) {
        throw IOException(readI18n("rwpp.browser.invalidResource"))
    }
    val directory = File(FileLoader.convertAbstractPath("/SD/rusted_warfare_maps")).toPath()
    Files.createDirectories(directory)
    val target = directory.resolve(file.name)
    if (Files.exists(target)) throw IOException(readI18n("rwpp.browser.resourceExists", file.name))
    val pending = Files.createTempFile(directory, ".rwpp-map-", ".part")
    try {
        Files.copy(file.toPath(), pending, StandardCopyOption.REPLACE_EXISTING)
        Files.move(pending, target)
    } finally {
        Files.deleteIfExists(pending)
    }
}
