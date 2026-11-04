/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable

/** Persistent room settings, with exact native map path and per-spawn player overrides. */
@Serializable
data class RoomPreset(
    val name: String,
    val mapPath: String,
    val mapType: String,
    val maxPlayers: Int,
    val sharedControl: Boolean,
    val startingCredits: Int,
    val startingUnits: Int,
    val fog: Int,
    val aiDifficulty: Int,
    val income: Float,
    val noNukes: Boolean,
    val allowSpectators: Boolean,
    val lockedRoom: Boolean,
    val teamLock: Boolean,
    val gameSpeed: Float = 1f,
    val baseMode: String? = null,
    val players: List<RoomPresetPlayer> = emptyList(),
    val recyclingDisabled: Boolean = false,
    val recyclingDisabledUnits: List<String> = emptyList(),
)

@Serializable
data class RoomPresetPlayer(val slot: Int, val team: Int, val color: Int?, val startingUnit: Int?)
