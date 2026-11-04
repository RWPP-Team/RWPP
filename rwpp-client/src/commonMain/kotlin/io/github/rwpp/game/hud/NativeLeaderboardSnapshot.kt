/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hud

import com.corrodinggames.rts.gameFramework.leaderboard.Leaderboard
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardColumn
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardGroupMode
import com.corrodinggames.rts.gameFramework.leaderboard.LeaderboardRow

internal data class AeroStatisticsRow(val name: String, val value: String, val color: Int, val group: Boolean)

/** Read on the game thread. Native column/row implementations own custom-resource and team totals. */
internal fun aeroStatisticsSnapshot(
    column: LeaderboardColumn,
    group: LeaderboardGroupMode
): List<AeroStatisticsRow> {
    val board = Leaderboard(column, group)
    board.buildRows()
    return board.rows.map { item ->
        val row = item as LeaderboardRow
        AeroStatisticsRow(
            row.name,
            Leaderboard.formatColumnValue(column, row.getColumnValue(column)),
            row.color,
            row.isGroup
        )
    }
}
