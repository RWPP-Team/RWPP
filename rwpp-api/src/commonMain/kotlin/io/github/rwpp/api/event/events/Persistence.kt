/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.gameFramework.GameSaver
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import com.corrodinggames.rts.gameFramework.network.OutputNetStream
import io.github.rwpp.api.event.AbstractEvent
import java.io.File

/** The native reason visible at the GameSaver boundary. */
enum class PersistenceReason {
    FILE_REQUEST,
    AUTO_SAVE,
    REPLAY_SNAPSHOT,
    NETWORK_SNAPSHOT,
    OTHER_STREAM,
}

/** A native file-save request or stream serialization. */
data class GameSaveRequest(
    val gameSaver: GameSaver,
    val reason: PersistenceReason,
    val name: String?,
    val file: File?,
    val autoSave: Boolean?,
    val outputStream: OutputNetStream?,
)

/** A native file-load request or stream deserialization. */
data class GameLoadRequest(
    val gameSaver: GameSaver,
    val reason: PersistenceReason,
    val name: String?,
    val file: File?,
    val inputStream: InputNetStream?,
    val keepTeams: Boolean?,
    val streamFlag2: Boolean?,
    val streamFlag3: Boolean?,
)

sealed interface PersistenceOperation {
    data class Saving(val request: GameSaveRequest) : PersistenceOperation
    data class Loading(val request: GameLoadRequest) : PersistenceOperation
}

enum class PersistenceFailureReason {
    RETURNED_FALSE,
    FILE_NOT_COMMITTED,
    THREW,
}

data class PersistenceFailure(
    val operation: PersistenceOperation,
    val reason: PersistenceFailureReason,
    val error: Throwable?,
)

data class PersistenceBeforeEvent(val operation: PersistenceOperation) : AbstractEvent()
data class PersistenceSavedEvent(val request: GameSaveRequest) : AbstractEvent()
data class PersistenceLoadedEvent(val request: GameLoadRequest) : AbstractEvent()
data class PersistenceFailedEvent(val failure: PersistenceFailure) : AbstractEvent()
