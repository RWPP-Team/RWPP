/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.game.map.parsePreviewXml
import java.io.*
import java.util.zip.GZIPInputStream
import com.corrodinggames.rts.game.map.Map as NativeMap

private const val MAX_REPLAY_MAP_BYTES = 16 * 1024 * 1024
private const val MAX_MAP_METADATA_BLOCK_BYTES = 32 * 1024 * 1024

internal data class ReplaySaveMap(val path: String, val embedded: ByteArray?)

internal class ReplayEmbeddedMap(
    id: Int,
    name: String,
    type: GameSetupMapType,
    path: String,
    val previewIdentity: String,
    bytes: ByteArray,
) : GameMap(id, name, type, { ByteArrayInputStream(bytes) }, path)

internal fun replayMap(replay: ParsedReplayItem): GameMap? {
    val initial = runCatching { ReplayFiles.open(replay.rawReplay).use(::readReplaySaveMap) }.getOrNull()
    val path = initial?.path?.replace('\\', '/')?.takeIf { it.endsWith(".tmx", ignoreCase = true) }
    val embedded = initial?.embedded?.takeIf(::isTmx)
        ?: runCatching { ReplayFiles.readMapSidecar(replay.rawReplay, ::readReplayMapBytes) }.getOrNull()
            ?.takeIf(::isTmx)
    val name = path?.substringAfterLast('/') ?: replay.mapName
    val type = if (path?.startsWith("maps/") == true) GameSetupMapType.skirmishMap else GameSetupMapType.customMap
    if (embedded != null) {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(embedded)
        return ReplayEmbeddedMap(
            replay.id, name, type, path ?: "maps/replay/$name.tmx",
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest), embedded,
        )
    }
    val localPath = path?.let(::replayLocalMapPath)
    if (localPath != null) {
        val local = runCatching { NativeMap.openMapStream(localPath)?.use(::readReplayMapBytes) }.getOrNull()
        if (local != null && isTmx(local)) return GameMap(
            replay.id, name, type, { ByteArrayInputStream(local) }, localPath,
        )
    }
    return replay.map.takeIf { it !is NetworkMap && it.mapType != GameSetupMapType.savedGame }
}

private fun replayLocalMapPath(path: String): String? {
    // Desktop records some generated maps as absolute paths rooted in the game directory.
    val root = File(".").absoluteFile.normalize().path.replace('\\', '/').trimEnd('/') + "/"
    val relative = if (path.startsWith(root, ignoreCase = true)) path.drop(root.length) else path
    if (relative.split('/').any { it == "." || it == ".." || ':' in it || '|' in it }) return null
    return relative.takeIf {
        listOf("maps/", "mods/maps/", "/SD/rusted_warfare_maps/", "/SD/rustedWarfare/maps/").any(it::startsWith)
    }
}

private fun isTmx(bytes: ByteArray): Boolean =
    runCatching { parsePreviewXml(bytes).documentElement.tagName == "map" }.getOrDefault(false)

/** Reads only the replay's initial save, without handing it to the live game engine. */
internal fun readReplaySaveMap(source: InputStream): ReplaySaveMap {
    val replay = DataInputStream(BufferedInputStream(source))
    require(replay.readUTF() == "rustedWarfareReplay")
    replay.readInt()
    require(replay.readInt() >= 11)
    replay.readUTF()
    replay.readBoolean()
    val save = replay.readBlock("gamesave")
    require(save.readUTF() == "rustedWarfareSave")
    save.readInt()
    val version = save.readInt()
    require(version in 11..96)
    save.readBoolean()
    val content = if (version >= 23) DataInputStream(GZIPInputStream(save.readBlock("saveCompression"))) else save
    return content.use {
        if (version >= 54) it.skipMetadataBlock("customUnitsBlock")
        if (version >= 56) it.skipMetadataBlock("gameSetup")
        val path = it.readUTF()
        val embedded = if (version >= 72 && it.readBoolean()) {
            val size = it.readInt()
            require(size in 1..MAX_REPLAY_MAP_BYTES)
            ByteArray(size).also(it::readFully)
        } else null
        ReplaySaveMap(path, embedded)
    }
}

private fun DataInputStream.readBlock(name: String): DataInputStream {
    require(readUTF() == name)
    val length = readInt()
    require(length >= 0)
    return DataInputStream(object : FilterInputStream(this) {
        private var remaining = length

        override fun read(): Int {
            if (remaining == 0) return -1
            return super.read().also { if (it >= 0) remaining-- }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0) return -1
            return super.read(bytes, offset, minOf(remaining, length)).also { if (it > 0) remaining -= it }
        }

        override fun available(): Int = minOf(remaining, super.available())

        override fun skip(count: Long): Long =
            super.skip(minOf(remaining.toLong(), count)).also { remaining -= it.toInt() }
    })
}

private fun DataInputStream.skipMetadataBlock(name: String) {
    require(readUTF() == name)
    var remaining = readInt()
    require(remaining in 0..MAX_MAP_METADATA_BLOCK_BYTES)
    while (remaining > 0) {
        val skipped = skipBytes(remaining)
        if (skipped == 0) {
            readByte()
            remaining--
        } else remaining -= skipped
    }
}

internal fun readReplayMapBytes(source: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = source.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= MAX_REPLAY_MAP_BYTES)
        output.write(buffer, 0, count)
    }
}
