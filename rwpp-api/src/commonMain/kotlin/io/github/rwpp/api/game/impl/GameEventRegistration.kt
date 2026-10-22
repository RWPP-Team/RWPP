/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.impl

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.events.PlayerJoinEvent
import io.github.rwpp.api.event.events.ReloadModFinishedEvent
import io.github.rwpp.api.event.events.ResumeGameEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.GlobalEventChannel
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** API-owned listeners for shared game lifecycle events. */
@Single(binds = [Initialization::class])
class GameEventRegistration : Initialization {
    override fun init() {
        val owner = EventOwner(ApiRuntime.extensionContext)
        GlobalEventChannel.filter(PlayerJoinEvent::class).subscribeAlways(owner) { event ->
            GameScope.launch {
                val room = appKoin.get<Game>().gameRoom
                room.teamMode?.onPlayerJoin(room, event.player)
            }
        }

        GlobalEventChannel.filter(ReloadModFinishedEvent::class).subscribeAlways(owner) {
            GameScope.launch { appKoin.get<Game>().getAllMaps(true) }
        }

        GlobalEventChannel.filter(ResumeGameEvent::class).subscribeAlways(owner) {
            GameScope.launch {
                val game = appKoin.get<Game>()
                NativeGameLifecycle.onResume()
                game.state.mods = GameEngine.getInstance().modManager.refreshModList()
                    .filterIsInstance<Mod>()
                    .filter { it.isEnabled }
                    .mapNotNull { it.getTitle() ?: it.name }
            }
        }
    }
}
