/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.event

import io.github.rwpp.api.event.hooks.PersistenceEventCallbacks
import io.github.rwpp.api.event.hooks.markReplaySnapshotSave
import kim.der.asm.api.annotation.*
import java.io.File
import io.github.rwpp.api.event.hooks.trackFileCommit as trackNativeFileCommit

@AsmMixin("com/corrodinggames/rts/gameFramework/GameSaver")
object PersistenceEventMixin {
    @WrapMethod(method = "autoSaveGame(Ljava/lang/String;Z)V", require = 1)
    fun wrapFileSave(name: String?, autoSave: Boolean, operation: Operation<Unit>) =
        PersistenceEventCallbacks.wrapFileSave(name, autoSave) { operation.call(name, autoSave) }

    @WrapMethod(method = "saveGame(Lcom/corrodinggames/rts/gameFramework/network/OutputNetStream;)V", require = 1)
    fun wrapStreamSave(outputStream: Any, operation: Operation<Unit>) =
        PersistenceEventCallbacks.wrapStreamSave(outputStream) { stream -> operation.call(stream) }

    @WrapMethod(method = "loadSaveFile(Ljava/lang/String;Z)Z", require = 1)
    fun wrapFileLoad(name: String?, keepTeams: Boolean, operation: Operation<Boolean>): Boolean =
        PersistenceEventCallbacks.wrapFileLoad(name, keepTeams) { fileName, keep ->
            operation.call(fileName, keep)
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
    ): Boolean =
        PersistenceEventCallbacks.wrapStreamLoad(inputStream, keepTeams, flag2, flag3) { stream, keep, second, third ->
            operation.call(stream, keep, second, third)
        }
}

/** The file rename is wrapped separately so other injection order cannot hide the commit point. */
@AsmMixin("com/corrodinggames/rts/gameFramework/files/FileLoader")
object FileSaveCommitMixin {
    @WrapMethod(method = "renameFile(Ljava/io/File;Ljava/io/File;)Z", require = 1)
    fun trackFileCommit(sourceFile: File, destinationFile: File, operation: Operation<Boolean>): Boolean =
        trackNativeFileCommit(destinationFile) { operation.call(sourceFile, destinationFile) }
}

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
    fun markReplaySnapshot(gameSaver: Any, outputStream: Any, operation: Operation<Unit>) {
        markReplaySnapshotSave { operation.call(gameSaver, outputStream) }
    }
}
