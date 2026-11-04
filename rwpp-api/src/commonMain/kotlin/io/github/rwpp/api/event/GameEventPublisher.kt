/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.event

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.ModManager
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import com.corrodinggames.rts.gameFramework.trigger.MapTrigger
import io.github.rwpp.api.State
import io.github.rwpp.api.event.events.*
import io.github.rwpp.api.event.hooks.broadcastNativeEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Native callback boundaries await the same coroutine channel used by extensions. */
object GameEventPublisher {
    private fun <E : Event> publish(event: E): E = broadcastNativeEvent(event)

    fun gameLoaded() = publish(GameLoadedEvent(GameEngine.getInstance()))
    fun startGame() = publish(StartGameEvent())
    fun refreshUi(delay: Duration = 0.milliseconds) = RefreshUIEvent().broadcastIn(delay = delay)
    fun hostGame() = publish(HostGameEvent())
    fun hostSinglePlayerGame() = publish(HostSinglePlayerGameEvent())
    fun joinGame(address: String, network: NetworkEngine? = null, connection: Connection? = null, team: Team? = null) =
        publish(JoinGameEvent(address, network, connection, team))

    fun playerJoin(player: Team, connection: Connection? = null) = publish(PlayerJoinEvent(player, connection))
    fun playerLeave(player: Team?, reason: String, connection: Connection? = null) = publish(
        PlayerLeaveEvent(
            player,
            reason,
            connection
        )
    )

    fun playerDefeated(player: Team) = publish(PlayerDefeatedEvent(player))
    fun quitGame() = publish(QuitGameEvent())
    fun returnMainMenu() = publish(ReturnMainMenuEvent())
    fun gameOver(gameTime: Int, winTeam: Int) = publish(GameOverEvent(gameTime, winTeam))
    fun mapChanged(mapName: String) = publish(MapChangedEvent(mapName))
    fun disconnect(reason: String, network: NetworkEngine? = null) = publish(DisconnectEvent(reason, network))
    fun triggerActivated(id: String, trigger: MapTrigger? = null) = publish(TriggerActivateEvent(id, trigger))
    fun localPlayerUnitDamaged() = publish(LocalPlayerUnitDamagedEvent())
    fun localPlayerBaseDamaged() = publish(LocalPlayerBaseDamagedEvent())
    fun localPlayerUnitCreated() = publish(LocalPlayerUnitCreatedEvent())
    fun localPlayerBuildingConstructed() = publish(LocalPlayerBuildingConstructedEvent())
    fun localPlayerUpgradeCompleted() = publish(LocalPlayerUpgradedCompletedEvent())
    fun reloadMod(manager: ModManager? = null, preserveMissing: Boolean = false, metadataOnly: Boolean = false) =
        publish(ReloadModEvent(manager, preserveMissing, metadataOnly))

    fun reloadModFinished(
        manager: ModManager? = null,
        preserveMissing: Boolean = false,
        metadataOnly: Boolean = false
    ) =
        publish(ReloadModFinishedEvent(manager, preserveMissing, metadataOnly))

    fun resumeGame() = publish(ResumeGameEvent(GameEngine.getInstance()))
    fun stateChanged(state: State) = publish(StateChangedEvent(state))

    suspend fun modCheck(requiredMods: List<String>) = ModCheckEvent(requiredMods).broadcast()

    fun systemMessage(
        message: String,
        onFinished: (SystemMessageEvent) -> Unit = {},
    ) = SystemMessageEvent(message).broadcastIn(onFinished = onFinished)

    fun chatMessage(
        sender: String,
        message: String,
        player: Team?,
        color: Int,
        onFinished: (ChatMessageEvent) -> Unit = {},
        origin: ChatOrigin = ChatOrigin.RECEIVE,
        connection: Connection? = null,
    ) = publish(ChatMessageEvent(sender, message, player, color, origin, connection)).also(onFinished)
}
