/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameSaver
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.api.event.events.GameLoadRequest
import io.github.rwpp.api.event.events.GameSaveRequest
import io.github.rwpp.api.event.events.PersistenceFailure
import io.github.rwpp.api.event.events.PersistenceFailureReason
import io.github.rwpp.api.event.events.PersistenceOperation
import io.github.rwpp.api.event.events.PersistenceReason
import io.github.rwpp.api.event.events.PersistenceBeforeEvent
import io.github.rwpp.api.event.events.PersistenceFailedEvent
import io.github.rwpp.api.event.events.PersistenceLoadedEvent
import io.github.rwpp.api.event.events.PersistenceSavedEvent
import java.io.File
import java.lang.reflect.InvocationTargetException
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod
import kim.der.asm.api.annotation.WrapOperation

private class FileSaveAttempt(
    val request: GameSaveRequest,
    var committed: Boolean = false,
)

/*
 * GameSaver.autoSaveGame catches its own write/rename errors. The successful native boundary is
 * the FileLoader.renameFile call returning true; this marker lets its outer wrapper emit FAILED
 * when native code swallowed an exception or returned before committing the final file.
 */
private val activeFileSave = ThreadLocal<FileSaveAttempt>()
private val activeReplaySnapshotSave = ThreadLocal<Boolean>()

@AsmMixin("com/corrodinggames/rts/gameFramework/GameSaver")
object PersistenceEventMixin {
    private val activeFileLoadDepth = ThreadLocal<Int>()

    @WrapMethod(method = "autoSaveGame(Ljava/lang/String;Z)V", require = 1)
    fun wrapFileSave(
        name: String?,
        autoSave: Boolean,
        operation: Operation<Unit>,
    ) {
        val saver = GameEngine.getInstance().gameSaver
        val request = GameSaveRequest(
            gameSaver = saver,
            reason = if (autoSave) PersistenceReason.AUTO_SAVE else PersistenceReason.FILE_REQUEST,
            name = name,
            file = resolveSaveFile(saver, name),
            autoSave = autoSave,
            outputStream = null,
        )
        val attempt = FileSaveAttempt(request)
        val previous = activeFileSave.get()
        activeFileSave.set(attempt)
        try {
            publishSaveBefore(PersistenceOperation.Saving(request))
            try {
                operation.call(name, autoSave)
            } catch (failure: Throwable) {
                val nativeFailure = unwrapPersistenceFailure(failure)
                if (!attempt.committed) {
                    fail(PersistenceOperation.Saving(request), PersistenceFailureReason.THREW, nativeFailure)
                }
                throw nativeFailure
            }

            if (!attempt.committed) {
                fail(
                    PersistenceOperation.Saving(request),
                    PersistenceFailureReason.FILE_NOT_COMMITTED,
                    error = null,
                )
            }
        } finally {
            restoreThreadLocal(activeFileSave, previous)
        }
    }

    @WrapMethod(method = "saveGame(Lcom/corrodinggames/rts/gameFramework/network/OutputNetStream;)V", require = 1)
    fun wrapStreamSave(
        outputStream: Any,
        operation: Operation<Unit>,
    ) {
        // File saves call saveGame internally; autoSaveGame owns their full commit boundary.
        if (activeFileSave.get() != null) {
            operation.call(outputStream)
            return
        }

        val engine = GameEngine.getInstance()
        val request = GameSaveRequest(
            gameSaver = engine.gameSaver,
            reason = when {
                activeReplaySnapshotSave.get() == true -> PersistenceReason.REPLAY_SNAPSHOT
                engine.networkEngine.networked -> PersistenceReason.NETWORK_SNAPSHOT
                else -> PersistenceReason.OTHER_STREAM
            },
            name = null,
            file = null,
            autoSave = null,
            outputStream = outputStream as OutputNetStream,
        )
        val event = PersistenceOperation.Saving(request)
        publishSaveBefore(event)
        try {
            operation.call(outputStream)
        } catch (failure: Throwable) {
            val nativeFailure = unwrapPersistenceFailure(failure)
            fail(event, PersistenceFailureReason.THREW, nativeFailure)
            throw nativeFailure
        }
        publishSaved(request)
    }

    @WrapMethod(method = "loadSaveFile(Ljava/lang/String;Z)Z", require = 1)
    fun wrapFileLoad(
        name: String?,
        keepTeams: Boolean,
        operation: Operation<Boolean>,
    ): Boolean {
        val saver = GameEngine.getInstance().gameSaver
        val request = GameLoadRequest(
            gameSaver = saver,
            reason = PersistenceReason.FILE_REQUEST,
            name = name,
            file = name?.let { saver.getSaveFile(it, false) },
            inputStream = null,
            keepTeams = keepTeams,
            streamFlag2 = null,
            streamFlag3 = null,
        )
        val event = PersistenceOperation.Loading(request)
        val previousDepth = activeFileLoadDepth.get() ?: 0
        activeFileLoadDepth.set(previousDepth + 1)
        try {
            publishSaveBefore(event)
            val loaded = try {
                operation.call(name, keepTeams)
            } catch (failure: Throwable) {
                val nativeFailure = unwrapPersistenceFailure(failure)
                fail(event, PersistenceFailureReason.THREW, nativeFailure)
                throw nativeFailure
            }

            if (loaded) {
                publishLoaded(request)
            } else {
                fail(event, PersistenceFailureReason.RETURNED_FALSE, error = null)
            }
            return loaded
        } finally {
            restoreThreadLocal(activeFileLoadDepth, previousDepth)
        }
    }

    @WrapMethod(
        method = "loadGameFromStream(Lcom/corrodinggames/rts/gameFramework/network/InputNetStream;ZZZ)Z",
        require = 1,
    )
    fun wrapStreamLoad(
        inputStream: Any,
        keepTeams: Boolean,
        flag2: Boolean,
        flag3: Boolean,
        operation: Operation<Boolean>,
    ): Boolean {
        // loadSaveFile provides the path-level event for its nested stream load.
        if ((activeFileLoadDepth.get() ?: 0) > 0) {
            return operation.call(inputStream, keepTeams, flag2, flag3)
        }

        val engine = GameEngine.getInstance()
        val request = GameLoadRequest(
            gameSaver = engine.gameSaver,
            reason = loadStreamReason(engine),
            name = null,
            file = null,
            inputStream = inputStream as InputNetStream,
            keepTeams = keepTeams,
            streamFlag2 = flag2,
            streamFlag3 = flag3,
        )
        val event = PersistenceOperation.Loading(request)
        publishSaveBefore(event)
        val loaded = try {
            operation.call(inputStream, keepTeams, flag2, flag3)
        } catch (failure: Throwable) {
            val nativeFailure = unwrapPersistenceFailure(failure)
            fail(event, PersistenceFailureReason.THREW, nativeFailure)
            throw nativeFailure
        }

        if (loaded) {
            publishLoaded(request)
        } else {
            fail(event, PersistenceFailureReason.RETURNED_FALSE, error = null)
        }
        return loaded
    }

    private fun resolveSaveFile(saver: Any, name: String?): File? {
        if (name == null) return null
        val nativeName = if (name.endsWith(".rwsave")) name else "$name.rwsave"
        return (saver as GameSaver).getSaveFile(nativeName, false)
    }

    private fun loadStreamReason(engine: GameEngine): PersistenceReason =
        when {
            engine.replayEngine.isPlayingReplay() -> PersistenceReason.REPLAY_SNAPSHOT
            engine.networkEngine.networked -> PersistenceReason.NETWORK_SNAPSHOT
            else -> PersistenceReason.OTHER_STREAM
        }

    private fun fail(
        operation: PersistenceOperation,
        reason: PersistenceFailureReason,
        error: Throwable?,
    ) {
        try {
            if (hasNativeEventListeners(PersistenceFailedEvent::class)) {
                broadcastNativeEvent(PersistenceFailedEvent(PersistenceFailure(operation, reason, error)))
            }
        } catch (notificationFailure: Throwable) {
            val nativeFailure = error
            if (nativeFailure == null || notificationFailure === nativeFailure) throw notificationFailure
            nativeFailure.addSuppressed(notificationFailure)
        }
    }

}

/** The file rename is wrapped on its own target class so mixin method order cannot hide this call. */
@AsmMixin("com/corrodinggames/rts/gameFramework/files/FileLoader")
object FileSaveCommitMixin {
    @WrapMethod(method = "renameFile(Ljava/io/File;Ljava/io/File;)Z", require = 1)
    fun trackFileCommit(
        sourceFile: File,
        destinationFile: File,
        operation: Operation<Boolean>,
    ): Boolean {
        val renamed = try {
            operation.call(sourceFile, destinationFile)
        } catch (failure: Throwable) {
            throw unwrapPersistenceFailure(failure)
        }
        val attempt = activeFileSave.get()
        val requestedFile = attempt?.request?.file
        if (renamed && attempt != null && requestedFile?.absolutePath == destinationFile.absolutePath) {
            attempt.committed = true
            publishSaved(attempt.request.copy(file = destinationFile))
        }
        return renamed
    }
}

private fun publishSaveBefore(operation: PersistenceOperation) {
    if (hasNativeEventListeners(PersistenceBeforeEvent::class)) {
        broadcastNativeEvent(PersistenceBeforeEvent(operation))
    }
}

private fun publishSaved(request: GameSaveRequest) {
    if (hasNativeEventListeners(PersistenceSavedEvent::class)) {
        broadcastNativeEvent(PersistenceSavedEvent(request))
    }
}

private fun publishLoaded(request: GameLoadRequest) {
    if (hasNativeEventListeners(PersistenceLoadedEvent::class)) {
        broadcastNativeEvent(PersistenceLoadedEvent(request))
    }
}

/** Marks only the initial gamesave serialization directly invoked by ReplayEngine.startSaving. */
@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
object ReplaySnapshotSaveMixin {
    @WrapOperation(
        method = "startSaving(Ljava/lang/String;)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.GameSaver.saveGame(Lcom/corrodinggames/rts/gameFramework/network/OutputNetStream;)V",
        ),
        require = 1,
    )
    fun markReplaySnapshot(
        gameSaver: Any,
        outputStream: Any,
        operation: Operation<Unit>,
    ) {
        val previous = activeReplaySnapshotSave.get()
        activeReplaySnapshotSave.set(true)
        try {
            operation.call(gameSaver, outputStream)
        } catch (failure: Throwable) {
            throw unwrapPersistenceFailure(failure)
        } finally {
            restoreThreadLocal(activeReplaySnapshotSave, previous)
        }
    }
}

private fun unwrapPersistenceFailure(failure: Throwable): Throwable {
    var cause = failure
    while (cause is InvocationTargetException && cause.targetException != null) {
        cause = cause.targetException
    }
    return cause
}

private fun <T : Any> restoreThreadLocal(threadLocal: ThreadLocal<T>, previous: T?) {
    if (previous == null) threadLocal.remove() else threadLocal.set(previous)
}
