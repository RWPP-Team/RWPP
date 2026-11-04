/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
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
import io.github.rwpp.api.event.events.ModCheckEvent
import io.github.rwpp.api.event.events.TeamWipedOutEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.logging.logger
import java.lang.reflect.Field
import java.util.*
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

internal object PlayerEventCallbacks {
    private val defeatFrames = ThreadLocal.withInitial { TeamDefeatFrames() }

    fun captureDefeatState(target: Any, @Suppress("UNUSED_PARAMETER") deltaTime: Float) {
        val team = target as Team
        defeatFrames.get().push(team, team.defeated, team.wipedOut)
    }

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

internal object ClientEventCallbacks {
    private val closedConnections = Collections.synchronizedMap(WeakHashMap<Connection, Boolean>())
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

internal object UnitMetadataEventCallbacks {
    private val modNameFields = ConcurrentHashMap<Class<*>, Field>()

    fun publishModCheck(mismatches: HashMap<*, *>): Boolean {
        val requiredMods = mismatches.values.mapNotNull { mismatch ->
            mismatch?.let {
                modNameFields.computeIfAbsent(it.javaClass) { type ->
                    type.getDeclaredField("modName").apply { isAccessible = true }
                }.get(it) as? String
            }
        }
        val event = ModCheckEvent(requiredMods)
        if (hasNativeEventListeners(ModCheckEvent::class)) broadcastNativeEvent(event)
        return event.isCancelled
    }
}

internal object MapTriggerEventCallbacks {
    fun publishTriggerActivation(
        @Suppress("UNUSED_PARAMETER") triggerEngine: Any,
        trigger: Any,
    ) {
        val native = trigger as MapTrigger
        GameEventPublisher.triggerActivated(native.id, native)
    }
}

internal object NetworkEventCallbacks {
    fun publishRemotePlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

    fun publishAIPlayerJoin(
        team: Any,
    ) {
        GameEventPublisher.playerJoin(team as Team)
    }

}

internal object RefreshUiEventCallbacks {
    fun publishRefreshUi() {
        GameEventPublisher.refreshUi()
    }
}

/** Publishes the API lifecycle after the native engine has completed its initialization. */
internal object EngineLoadedCallbacks {
    fun publishGameLoaded(
        @Suppress("UNUSED_PARAMETER") target: Any,
        @Suppress("UNUSED_PARAMETER") context: Any,
    ) {
        ApiRuntime.onGameLoaded()
    }
}
