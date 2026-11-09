/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import io.github.rwpp.api.event.AbstractEvent

enum class GameRecoveryStage { WAITING_FOR_SERVER, RESUMED, SESSION_ENDED }

/** A bounded recovery attempt or a safely stopped native session, never a silent frame skip. */
data class GameRecoveryEvent(
    val id: Long,
    val stage: GameRecoveryStage,
    val detail: String,
    val error: Throwable? = null,
) : AbstractEvent()
