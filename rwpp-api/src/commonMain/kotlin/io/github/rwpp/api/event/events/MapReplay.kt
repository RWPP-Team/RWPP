/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event.events

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameEngineMapType
import com.corrodinggames.rts.gameFramework.ReplayEngine
import io.github.rwpp.api.event.AbstractEvent

enum class MapLoadPhase { BEFORE, LOADED, FAILED }

/** One lifecycle point for a native map load. */
data class MapLoadEvent(
    val engine: GameEngine,
    val path: String?,
    val type: GameEngineMapType,
    val loadAi: Boolean,
    val keepTeams: Boolean,
    val phase: MapLoadPhase,
    val error: Throwable? = null,
) : AbstractEvent()

enum class ReplayLoadPhase { BEFORE, LOADED, FAILED }

/** Native replay loading, including normal false returns and thrown failures. */
data class ReplayLoadEvent(
    val engine: ReplayEngine,
    val name: String,
    val phase: ReplayLoadPhase,
    val error: Throwable? = null,
) : AbstractEvent()
