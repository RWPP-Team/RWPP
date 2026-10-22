/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.event.hooks

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.Connection
import com.corrodinggames.rts.gameFramework.trigger.MapTrigger
import io.github.rwpp.api.ApiRuntime
import io.github.rwpp.api.appKoin
import io.github.rwpp.api.event.GameEventPublisher
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import io.github.rwpp.api.event.events.ModCheckEvent
import io.github.rwpp.api.event.events.TeamWipedOutEvent
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private class TeamDefeatSnapshot {
    var team: Team? = null
        private set
    var defeated: Boolean = false
        private set
    var wipedOut: Boolean = false
        private set

    fun capture(team: Team, defeated: Boolean, wipedOut: Boolean) {
        this.team = team
        this.defeated = defeated
        this.wipedOut = wipedOut
    }

    fun clear() {
        team = null
        defeated = false
        wipedOut = false
    }
}

private class TeamDefeatFrames {
    private val snapshots = ArrayList<TeamDefeatSnapshot>()
    private var depth = 0

    fun push(team: Team, defeated: Boolean, wipedOut: Boolean) {
        val snapshot = if (depth < snapshots.size) snapshots[depth] else {
            TeamDefeatSnapshot().also(snapshots::add)
        }
        snapshot.capture(team, defeated, wipedOut)
        depth++
    }

    fun pop(): TeamDefeatSnapshot? {
        if (depth == 0) return null
        return snapshots[--depth]
    }
}

@AsmMixin("com/corrodinggames/rts/game/Team")
object PlayerEventMixin {
    private val defeatFrames = ThreadLocal.withInitial { TeamDefeatFrames() }

    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.HEAD, require = 1)
    fun captureDefeatState(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val team = target as Team
        defeatFrames.get().push(team, team.defeated, team.wipedOut)
    }

    @AsmInject(method = "updateDefeatCheck(F)V", target = InjectionPoint.TAIL, require = 1)
    fun publishDefeatAndGameOver(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val player = target as Team
        val frames = defeatFrames.get()
        val before = frames.pop() ?: return
        val beforeTeam = before.team
        val wasDefeated = before.defeated
        val wasWipedOut = before.wipedOut
        before.clear()
        if (beforeTeam !== player) return

        val newlyDefeated = !wasDefeated && player.defeated
        val newlyWipedOut = !wasWipedOut && player.wipedOut
        if (!newlyDefeated && !newlyWipedOut) return

        val game = appKoin.get<Game>()
        if (newlyWipedOut && hasNativeEventListeners(TeamWipedOutEvent::class)) {
            broadcastNativeEvent(TeamWipedOutEvent(player))
        }
        if (newlyDefeated) GameEventPublisher.playerDefeated(player)

        if (!game.state.isPlaying() || game.state.gameOver) return

        var lastTeam: Int? = null
        for (slot in 0 until game.gameRoom.maxPlayerCount) {
            val candidate = Team.getTeam(slot) ?: continue
            if (candidate.allyTeam == -3 || candidate.wipedOut || candidate.defeated || candidate.eliminated) continue
            if (lastTeam != null && lastTeam != candidate.allyTeam) return
            lastTeam = candidate.allyTeam
        }

        if (lastTeam != null) {
            logger().info("Game Over! Team $lastTeam wins!")
            GameEventPublisher.gameOver(GameEngine.getInstance().gameTimeMillis, lastTeam)
            game.state.gameOver = true
        }
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/Connection")
object ClientEventMixin {
    private val closedConnections = Collections.synchronizedMap(WeakHashMap<Connection, Boolean>())
    @AsmInject(
        method = "handleRemoteDisconnect(ZZLjava/lang/String;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishPlayerLeave(
        connection: Any,
        @Suppress("UNUSED_PARAMETER") graceful: Boolean,
        @Suppress("UNUSED_PARAMETER") timeout: Boolean,
        reason: String?,
    ) {
        val native = connection as Connection
        if (closedConnections.put(native, true) != null) return
        val player = native.player as? Team
        GameEventPublisher.playerLeave(player, CommonUtils.escapeXml(reason.orEmpty()), native)
    }
}

@AsmMixin("com/corrodinggames/rts/game/units/custom/CustomUnitMetadata")
object UnitMetadataEventMixin {
    private val modNameFields = ConcurrentHashMap<Class<*>, Field>()

    @AsmInject(
        method = "reportUnitMismatch(Lcom/corrodinggames/rts/game/units/custom/UnitMismatchInfo;Ljava/util/HashMap;)V",
        target = InjectionPoint.HEAD,
        cancellable = true,
        require = 1,
    )
    fun publishModCheck(
        callbackInfo: CallbackInfo,
        @Suppress("UNUSED_PARAMETER") mismatchInfo: Any,
        mismatches: HashMap<*, *>,
    ) {
        val requiredMods = mismatches.values.mapNotNull { mismatch ->
            mismatch?.let {
                modNameFields.computeIfAbsent(it.javaClass) { type ->
                    type.getDeclaredField("modName").apply { isAccessible = true }
                }.get(it) as? String
            }
        }
        val event = ModCheckEvent(requiredMods)
        if (hasNativeEventListeners(ModCheckEvent::class)) broadcastNativeEvent(event)
        if (event.isCancelled) callbackInfo.cancel()
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/trigger/TriggerActivator")
object MapTriggerEventMixin {
    @AsmInject(
        method = "activate(Lcom/corrodinggames/rts/gameFramework/trigger/TriggerEngine;Lcom/corrodinggames/rts/gameFramework/trigger/MapTrigger;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishTriggerActivation(
        @Suppress("UNUSED_PARAMETER") triggerEngine: Any,
        trigger: Any,
    ) {
        val native = trigger as MapTrigger
        GameEventPublisher.triggerActivated(native.id, native)
    }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/network/NetworkEngine")
object NetworkEventMixin {
    @AsmInject(
        method = "processSystemPacket(Lcom/corrodinggames/rts/gameFramework/network/Packet;)V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishRemotePlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

    @AsmInject(
        method = "addAIToGame()V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com.corrodinggames.rts.gameFramework.network.NetworkCallbacks.onPlayerAdded"
                + "(Lcom/corrodinggames/rts/game/Team;)V",
            shift = Shift.AFTER,
        ),
        require = 1,
    )
    fun publishAIPlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

}

@AsmMixin("com/corrodinggames/rts/appFramework/MultiplayerBattleroomActivity")
object RefreshUiEventMixin {
    @AsmInject(method = "updateUI()V", target = InjectionPoint.TAIL, require = 1)
    fun publishRefreshUi() {
        GameEventPublisher.refreshUi()
    }
}

/** Publishes the API lifecycle after the native engine has completed its initialization. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object EngineLoadedMixin {
    @AsmInject(
        method = "init(Landroid/content/Context;)V",
        target = InjectionPoint.TAIL,
        require = 1,
    )
    fun publishGameLoaded(
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") context: Any,
    ) {
        ApiRuntime.onGameLoaded()
    }
}
