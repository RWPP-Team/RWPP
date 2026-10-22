/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui.color

import androidx.compose.ui.graphics.Color

private val _teamColors = listOf(
    Color.Green,
    Color.Red,
    Color.Blue,
    Color.Yellow,
    Color.Cyan,
    Color(249, 246, 238),
    Color.Black,
    Color(255, 0, 255),
    Color(237, 112, 20),
    Color(51, 0, 102)
)

@Suppress("MemberVisibilityCanBePrivate")
val teamColors
    get() = _teamColors

fun getTeamColor(team: Int): Color = teamColors.getOrNull(team) ?: Color.Gray