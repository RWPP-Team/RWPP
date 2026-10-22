/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import io.github.rwpp.api.game.Game
import com.corrodinggames.rts.game.Team
import io.github.rwpp.api.i18n.readI18n
import org.koin.compose.koinInject

@Composable
actual fun KickPlayerContextMenuAreaMultiplatform(player: Team, content: @Composable (() -> Unit)) {
    val room = koinInject<Game>().gameRoom
    ContextMenuArea(
        items = {
            if ((room.isHost || room.isHostServer) && room.localPlayer != player) {
                listOf(
                    ContextMenuItem(
                        readI18n("rwpp.multiplayer.room.kick")
                    ) {
                        room.kickPlayer(player)
                    }
                )
            } else {
                emptyList()
            }
        },
        content = content
    )
}