/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.files.FileLoader
import io.github.rwpp.api.game.map.Replay
import java.io.*

internal data class ReplayTimelineMetadata(
    /** Absolute, zero-based tick written by the replay writer's final wait block. */
    val stoppedFrame: Int,
    val gameTimeMillis: Int,
    val commandCount: Int,
)

/** Use the same abstract replay directory and mounted-storage backend as the game. */
internal object ReplayFiles {
    /** FileLoader treats relative paths as virtual asset paths on desktop. File-based
     * replay APIs resolve the same File using its absolute path, so use that form
     * for existence checks as well (including SAF mounted paths on Android).
     */
    internal fun fileExistsAtAbsolutePath(file: File, exists: (String) -> Boolean): Boolean =
        exists(file.absolutePath)

    private fun fileExists(file: File): Boolean =
        fileExistsAtAbsolutePath(file) { FileLoader.fileExists(it) }

    private fun file(name: String, suffix: String = ""): File {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Invalid replay filename"
        }
        return GameEngine.getInstance().replayEngine.getReplayFile(name + suffix, true)
    }

    private fun file(replay: Replay, suffix: String = ""): File = file(replay.name, suffix)

    private fun validateNewName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty() && name != "." && name != "..") { "Replay name cannot be empty" }
        require(name.none { it.isISOControl() || it in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') }) {
            "Replay name contains characters that are not allowed in a filename"
        }
        require(!name.endsWith(' ') && !name.endsWith('.')) { "Replay name cannot end with a space or period" }
        return name
    }

    fun open(replay: Replay): InputStream = checkNotNull(FileLoader.openByPath(file(replay))) {
        "Cannot open replay ${replay.name}"
    }

    /** Reads and validates complete end metadata while streaming past the potentially large save block. */
    fun readTimelineMetadata(replayName: String): ReplayTimelineMetadata? {
        val source = try {
            FileLoader.openByPath(file(replayName))
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            source.use { readTimelineMetadata(it) }
        } catch (_: Exception) {
            null
        }
    }

    internal fun readTimelineMetadata(source: InputStream): ReplayTimelineMetadata? {
        return try {
            val input = DataInputStream(BufferedInputStream(source))
            if (input.readUTF() != "rustedWarfareReplay") return null
            input.readInt() // Engine version code.
            val saveVersion = input.readInt()
            if (saveVersion < 11) return null
            input.readUTF() // Platform details.
            input.readBoolean() // Demo version.

            var sawGameSave = false
            var sawEnd = false
            var finalWaitFrame: Int? = null
            var previousBlockName: String? = null
            while (true) {
                val blockName = try {
                    input.readUTF()
                } catch (_: EOFException) {
                    return null
                }
                val blockLength = input.readInt()
                if (blockLength < 0) return null

                when (blockName) {
                    "gamesave" -> {
                        if (sawGameSave || sawEnd) return null
                        skipFully(input, blockLength)
                        sawGameSave = true
                    }

                    "wait" -> {
                        if (sawEnd) return null
                        if (blockLength != Int.SIZE_BYTES) return null
                        finalWaitFrame = input.readInt()
                    }

                    "end" -> {
                        if (sawEnd || blockLength != 0 || finalWaitFrame == null) return null
                        sawEnd = true
                    }

                    "endReplayMetaData" -> {
                        if (!sawGameSave || !sawEnd || previousBlockName != "end" ||
                            blockLength > MAX_REPLAY_METADATA_BYTES
                        ) return null
                        val payload = ByteArray(blockLength)
                        input.readFully(payload)
                        val metadata = DataInputStream(ByteArrayInputStream(payload))
                        val metadataVersion = metadata.readByte().toInt()
                        val stoppedFrame = metadata.readInt()
                        val stoppedTimeMillis = metadata.readInt()
                        val commandCount = metadata.readInt()
                        val resyncCount = metadata.readInt()
                        val textMetadata = metadata.readUTF()
                        val expectedText =
                            "{frames:$stoppedFrame,time:$stoppedTimeMillis,commandCount:$commandCount,resyncCount:$resyncCount}"

                        if (metadata.available() != 0 || metadataVersion != 0 ||
                            stoppedFrame < 0 || stoppedFrame == Int.MAX_VALUE ||
                            stoppedTimeMillis < 0 || commandCount < 0 || resyncCount < 0 ||
                            finalWaitFrame != stoppedFrame || textMetadata != expectedText
                        ) {
                            return null
                        }
                        return ReplayTimelineMetadata(
                            stoppedFrame = stoppedFrame,
                            gameTimeMillis = stoppedTimeMillis,
                            commandCount = commandCount,
                        )
                    }

                    else -> {
                        if (sawEnd) return null
                        skipFully(input, blockLength)
                    }
                }
                previousBlockName = blockName
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun skipFully(input: DataInputStream, byteCount: Int) {
        var remaining = byteCount
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw EOFException("Truncated replay block")
            if (read == 0) {
                if (input.read() < 0) throw EOFException("Truncated replay block")
                remaining--
            } else {
                remaining -= read
            }
        }
    }

    private const val MAX_REPLAY_METADATA_BYTES = 4096

    fun sourcePath(replay: Replay): String = file(replay).absolutePath

    /** Rename the replay and its optional companion map through the game's storage backend. */
    fun rename(replay: Replay, requestedName: String): String {
        val oldName = replay.name
        val oldHasExtension = oldName.endsWith(".replay", ignoreCase = true)
        val requested = validateNewName(requestedName)
        val newName = if (oldHasExtension && !requested.endsWith(".replay", ignoreCase = true)) {
            "$requested.replay"
        } else {
            requested
        }
        validateNewName(newName)

        if (newName.equals(oldName, ignoreCase = true)) return oldName

        val source = file(oldName)
        val destination = file(newName)
        val sourceCompanion = file(oldName, ".map")
        val destinationCompanion = file(newName, ".map")
        val hasSourceCompanion = fileExists(sourceCompanion)
        check(!fileExists(destination)) { "A replay named $newName already exists" }
        check(
            !fileExists(destinationCompanion) || sourceCompanion.absolutePath.equals(
                destinationCompanion.absolutePath,
                ignoreCase = true
            )
        ) {
            "A companion map for $newName already exists"
        }

        check(FileLoader.renameFile(source, destination)) { "Cannot rename replay $oldName" }
        if (hasSourceCompanion) {
            try {
                check(FileLoader.renameFile(sourceCompanion, destinationCompanion)) {
                    "Cannot rename companion map for $oldName"
                }
            } catch (failure: Throwable) {
                val rolledBack = runCatching { FileLoader.renameFile(destination, source) }.getOrDefault(false)
                if (!rolledBack) {
                    throw IllegalStateException(
                        "Replay was renamed to $newName but its companion map could not be moved and rollback failed",
                        failure,
                    )
                }
                throw failure
            }
        }
        return newName
    }

    fun delete(replay: Replay) {
        check(FileLoader.deleteFile(file(replay))) { "Cannot delete replay ${replay.name}" }
        // The game keeps a companion map beside some recordings.
        val companion = file(replay, ".map")
        if (fileExists(companion)) {
            check(FileLoader.deleteFile(companion)) { "Cannot delete companion map for ${replay.name}" }
        }
    }
}
