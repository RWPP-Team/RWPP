/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.api.Initialization
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.RwppExtensionRuntime
import io.github.rwpp.ui.UI
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import com.corrodinggames.rts.gameFramework.CommandControllerCommand
import com.corrodinggames.rts.game.units.buildings.FactoryInterface
import com.corrodinggames.rts.gameFramework.utility.FastArrayList
import io.github.rwpp.api.event.EventOwner
import io.github.rwpp.api.event.EventPriority
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.ChatMessageEvent
import io.github.rwpp.api.event.events.ChatOrigin
import io.github.rwpp.api.event.events.CommandPathsReadyEvent
import io.github.rwpp.api.event.events.UnitDrawEvent
import io.github.rwpp.api.event.events.UnitOverlayBarEvent
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
        }
        GlobalEventChannel.filter(UnitOverlayBarEvent::class).subscribeAlways(owner) { draw ->
            unitDrawing.onDrawBar(draw.unit, draw.graphics, draw.rect, draw.paint)
            draw.cancel()
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
        GlobalEventChannel.filter(ChatMessageEvent::class).subscribeAlways(owner, priority = EventPriority.MONITOR) { chat ->
            if (chat.origin != ChatOrigin.RECEIVE || chat.isCancelled) return@subscribeAlways
            val sender = chat.sender.orEmpty()
            val message = chat.message
            val color = chat.color
            GameScope.launch(Dispatchers.Main) { UI.onReceiveChatMessage(sender, message, color) }
        }
    }
}
