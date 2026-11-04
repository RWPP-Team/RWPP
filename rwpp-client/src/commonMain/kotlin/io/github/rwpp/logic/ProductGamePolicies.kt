/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic

import com.corrodinggames.rts.game.units.buildings.FactoryInterface
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.EventPriority
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.chat.shouldClearChatAfterGameQuit
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.graphics.drawCollisionBounds
import io.github.rwpp.ui.UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** RWPP policy registered against shared native hooks once, for both hosts. */
@Single(binds = [Initialization::class])
class ProductGamePolicies(
    private val settings: Settings,
    private val unitDrawing: EntityRangeUnitComp,
) : Initialization {
    private val commandUnits by lazy {
        CommandControllerCommand::class.java.getDeclaredField("units").apply { isAccessible = true }
    }

    override fun init() {
        val owner = EventOwner(RwppExtensionRuntime.extensionContext)
        GlobalEventChannel.filter(UnitDrawEvent::class).subscribeAlways(owner) { draw ->
            unitDrawing.onDraw(draw.unit, draw.graphics, draw.deltaTime)
            if (settings.showCollisionBoxes) drawCollisionBounds(draw.unit, draw.graphics)
        }
        GlobalEventChannel.filter(UnitOverlayBarEvent::class).subscribeAlways(owner) { draw ->
            unitDrawing.onDrawBar(draw.unit, draw.graphics, draw.rect, draw.paint)
            draw.cancel()
        }
        GlobalEventChannel.filter(ReloadModEvent::class).subscribeAlways(owner) {
            // Pending recipes may refer to textures disposed by the native mod loader.
            io.github.rwpp.graphics.ActionImages.clear()
        }
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(owner) {
            io.github.rwpp.ui.ChatMute.clear()
            UI.clearChatMessages()
        }
        GlobalEventChannel.filter(QuitGameEvent::class).subscribeAlways(owner) {
            val network = runCatching { com.corrodinggames.rts.gameFramework.GameEngine.getInstance().networkEngine }
                .getOrNull()
            if (network == null || shouldClearChatAfterGameQuit(
                    networked = network.networked,
                    singlePlayerServer = network.singlePlayerServer,
                    gameStarted = network.gameStarted,
                )
            ) {
                UI.clearChatMessages()
            }
        }
        GlobalEventChannel.filter(ChatMessageEvent::class)
            .subscribeAlways(owner, priority = EventPriority.HIGH) { chat ->
                if (chat.origin == ChatOrigin.RECEIVE) {
                    val players = appKoin.get<io.github.rwpp.api.game.Game>().gameRoom.getPlayers()
                    if (io.github.rwpp.ui.ChatMute.filters(chat, players)) chat.cancel()
                }
            }
        GlobalEventChannel.filter(CommandPathsReadyEvent::class).subscribeAlways(owner) { event ->
            val command = event.command
            if (settings.enhancedReinforceTroops && command.actionId.id != "-1") {
                val units = commandUnits.get(command) as? FastArrayList
                if (units != null) {
                    val sorted = units.sortedBy { (it as? FactoryInterface)?.queuedItems?.size ?: 0 }
                    commandUnits.set(command, FastArrayList(sorted))
                }
            }
        }
        GlobalEventChannel.filter(ChatMessageEvent::class)
            .subscribeAlways(owner, priority = EventPriority.MONITOR) { chat ->
                if (chat.origin != ChatOrigin.RECEIVE || chat.isCancelled) return@subscribeAlways
                val sender = chat.sender
                val message = chat.message
                val color = chat.color
                val transcriptGeneration = UI.currentChatTranscriptGeneration
                GameScope.launch(Dispatchers.Main) {
                    UI.onReceiveChatMessage(sender, message, color, transcriptGeneration)
                }
            }
    }
}
