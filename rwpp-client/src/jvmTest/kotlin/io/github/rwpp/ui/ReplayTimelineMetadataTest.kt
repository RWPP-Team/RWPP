package io.github.rwpp.ui

import io.github.rwpp.game.replay.ReplayFiles
import io.github.rwpp.game.replay.ReplayTimelineMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ReplayTimelineMetadataTest {
    @Test
    fun checksRelativeReplayFilesUsingTheAbsoluteStoragePath() {
        val workingDirectory = File(".").canonicalFile.toPath()
        val temporaryDirectory = Files.createTempDirectory(workingDirectory, "rwpp-replay-files-")
        try {
            val companionPath = temporaryDirectory.resolve("sample.replay.map")
            Files.writeString(companionPath, "companion")
            val relativeFile = workingDirectory.relativize(companionPath).toFile()
            assertFalse(relativeFile.isAbsolute)

            var queriedPath: String? = null
            val exists = ReplayFiles.fileExistsAtAbsolutePath(relativeFile) { path ->
                queriedPath = path
                File(path).isAbsolute && File(path).isFile
            }

            assertEquals(relativeFile.absolutePath, queriedPath)
            assertTrue(exists)
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun readsCompleteMetadataAfterStreamingPastLargeInitialSave() {
        val result = parse(replayBytes(stoppedFrame = 272, commandCount = 1))

        assertEquals(
            ReplayTimelineMetadata(stoppedFrame = 272, gameTimeMillis = 12_340, commandCount = 1),
            result,
        )
    }

    @Test
    fun preservesZeroCommandCountForTheNativeReaderLimitation() {
        val result = parse(replayBytes(stoppedFrame = 80, commandCount = 0))

        assertEquals(0, result?.commandCount)
        assertEquals(80, result?.stoppedFrame)
    }

    @Test
    fun rejectsIncompleteOrInconsistentEndMetadata() {
        val inconsistentWait = replayBytes(stoppedFrame = 272, commandCount = 1, waitFrame = 271)
        val truncatedMetadata = replayBytes(stoppedFrame = 272, commandCount = 1).dropLast(3).toByteArray()

        assertNull(parse(inconsistentWait))
        assertNull(parse(truncatedMetadata))
    }

    private fun parse(bytes: ByteArray) = ReplayFiles.readTimelineMetadata(ByteArrayInputStream(bytes))

    private fun replayBytes(
        stoppedFrame: Int,
        commandCount: Int,
        waitFrame: Int = stoppedFrame,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { replay ->
            replay.writeUTF("rustedWarfareReplay")
            replay.writeInt(1) // Engine version code.
            replay.writeInt(96) // Block-based replay save version.
            replay.writeUTF("test platform")
            replay.writeBoolean(false)
            writeBlock(replay, "gamesave", ByteArray(2 * 1024 * 1024))
            val waitBytes = ByteArrayOutputStream()
            DataOutputStream(waitBytes).use { it.writeInt(waitFrame) }
            writeBlock(replay, "wait", waitBytes.toByteArray())
            writeBlock(replay, "end", byteArrayOf())

            val metadataBytes = ByteArrayOutputStream()
            DataOutputStream(metadataBytes).use { metadata ->
                metadata.writeByte(0)
                metadata.writeInt(stoppedFrame)
                metadata.writeInt(12_340)
                metadata.writeInt(commandCount)
                metadata.writeInt(0)
                metadata.writeUTF(
                    "{frames:$stoppedFrame,time:12340,commandCount:$commandCount,resyncCount:0}"
                )
            }
            writeBlock(replay, "endReplayMetaData", metadataBytes.toByteArray())
        }
        return bytes.toByteArray()
    }

    private fun writeBlock(output: DataOutputStream, name: String, payload: ByteArray) {
        output.writeUTF(name)
        output.writeInt(payload.size)
        output.write(payload)
    }
}
