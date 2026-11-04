/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.map

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.files.FileLoader
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import com.corrodinggames.rts.game.map.Map as NativeMap

private const val MAP_EXPORT_DIRECTORY = "/SD/rustedWarfare/maps"

internal fun validExportMapName(value: String): Boolean {
    val name = value.trim()
    if (name != value || name.isEmpty() || name.length > 100 || name == "." || name == ".." ||
        name.endsWith('.') || name.any { it.isISOControl() || it in "\\/:*?\"<>|" }
    ) return false
    val stem = name.substringBefore('.').uppercase(Locale.ROOT)
    return stem !in setOf(
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
        "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}

/** Uses the original TMX received in the start packet, or the host's native map path. */
internal fun <T> withCurrentMapSource(engine: GameEngine, block: (InputStream) -> T): T {
    val network = engine.networkEngine
    val incoming = if (!network.isServer) engine.inputNetStream
        ?: network.customMapStream?.takeIf { network.gameSetup.mapType == GameSetupMapType.customMap } else null
    if (incoming != null) {
        val source = incoming.inputStream
        check(source.markSupported()) { "The received map stream cannot be rewound" }
        return synchronized(source) {
            source.reset()
            try {
                // A parser may close its input; the engine still owns this stream.
                block(object : FilterInputStream(source) {
                    override fun close() = Unit
                })
            } finally {
                source.reset()
            }
        }
    }
    val paths = listOfNotNull(engine.currentMapPath, network.currentGameMapPath, network.getNetworkMapPath())
        .filter { it.isNotBlank() && !it.startsWith("STEAM:") && !it.startsWith("SAVE:") }
        .distinct()
    for (path in paths) {
        val source = runCatching { NativeMap.openMapStream(path) }.getOrNull() ?: continue
        return source.use(block)
    }
    throw IllegalStateException("The room's map data is unavailable")
}

/** Keeps ordinary network exports byte-for-byte; sandbox exports merge exact TerrainTiler writes. */
internal fun writeNetworkMapExportContent(
    sandbox: Boolean,
    map: NativeMap?,
    source: InputStream,
    output: OutputStream,
) {
    if (sandbox) {
        writeSandboxMapWithTerrain(checkNotNull(map) { "The sandbox map is unavailable" }, source, output)
    } else {
        source.copyTo(output)
    }
}

/** Regular rooms export the received original; the sandbox uses the native editor export. */
internal fun exportCurrentNetworkMap(engine: GameEngine, name: String): String {
    require(validExportMapName(name)) { "Invalid map name" }
    val sandbox = engine.networkEngine.sandboxMode || engine.debugEditorMode
    check((engine.networkEngine.networked || sandbox) && engine.map != null && engine.map.mapLoaded) {
        "No network or sandbox map is loaded"
    }
    val filename = if (name.endsWith(".tmx", true)) name else "$name.tmx"
    val target = "$MAP_EXPORT_DIRECTORY/$filename"
    check(!FileLoader.fileExists(target)) { "A map with this name already exists" }
    val staging = File.createTempFile("rwpp-map-export-", ".tmx")
    try {
        withCurrentMapSource(engine) { source ->
            staging.outputStream().use { output ->
                writeNetworkMapExportContent(sandbox, engine.map, source, output)
            }
        }
        check(staging.length() > 0) { "The map export is empty" }
        if (!FileLoader.isDirectory(MAP_EXPORT_DIRECTORY)) {
            check(FileLoader.createDirectory(MAP_EXPORT_DIRECTORY) || FileLoader.isDirectory(MAP_EXPORT_DIRECTORY)) {
                "Cannot create the custom maps directory"
            }
        }
        check(!FileLoader.fileExists(target)) { "A map with this name already exists" }
        try {
            val output = checkNotNull(FileLoader.openOutput(target, false)) { "Cannot open the map destination" }
            staging.inputStream().use { source -> output.use { source.copyTo(it) } }
        } catch (failure: Exception) {
            runCatching { FileLoader.deleteFile(File(FileLoader.convertAbstractPath(target))) }
            throw failure
        }
        return FileLoader.convertAbstractPathForDisplay(target)
    } finally {
        staging.delete()
    }
}

