/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.team

import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.game.GameRoom

abstract class TeamMode(val name: String) {
    open val displayName: String get() = name
    abstract fun onPlayerJoin(gameRoom: GameRoom, player: Team)
    abstract fun onInit(gameRoom: GameRoom)
    open fun autoTeamAssign(gameRoom: GameRoom, targetSpawnPoint: Int, player: Team): Int = player.allyTeam
}
