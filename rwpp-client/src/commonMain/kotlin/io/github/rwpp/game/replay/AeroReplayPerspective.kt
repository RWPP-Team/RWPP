/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.i18n.readI18n
import com.corrodinggames.rts.game.map.Map as NativeMap

internal data class AeroReplayPerspectiveOption(val teamId: Int, val label: String)

/** Shared with the passive world overlay. Replay access changes only what the HUD displays. */
internal object AeroReplayControlsState {
    var showAllUnitOrders by mutableStateOf(false)
}

internal fun aeroReplayPerspectiveOptions(): List<AeroReplayPerspectiveOption> = buildList {
    add(AeroReplayPerspectiveOption(Team.grayTeam.teamId, readI18n("rwpp.replay.globalObserver")))
    for (id in 0 until Team.maxTeamId) {
        val team = Team.getTeam(id) ?: continue
        if (team.isSpectator) continue
        val name = team.name?.trim()?.takeIf(String::isNotEmpty)
            ?: team.colorName?.takeIf(String::isNotEmpty)
            ?: readI18n("rwpp.replay.player", id + 1)
        add(AeroReplayPerspectiveOption(team.teamId, name))
    }
}

internal data class AeroReplayTeamRestore(
    val engine: GameEngine,
    val map: NativeMap,
    val team: Team?,
    val presentationOnly: Boolean = false,
)

/** Keeps a temporary replay perspective from escaping into the next game. */
internal class AeroReplayPerspectiveSession {
    private var engine: GameEngine? = null
    private var map: NativeMap? = null
    private var originalTeam: Team? = null
    private var presentationOnly = false
    var active: Boolean = false
        private set

    fun matches(engine: GameEngine): Boolean = this.engine === engine && this.map === engine.map

    fun begin(engine: GameEngine) {
        if (active) return
        this.engine = engine
        map = engine.map
        originalTeam = engine.playerTeam
        presentationOnly = !engine.replayEngine.isPlayingReplay
        active = true
    }

    fun finish(currentEngine: GameEngine): AeroReplayTeamRestore? {
        if (!active) return null
        val expectedEngine = engine
        val expectedMap = map
        val restore =
            if (expectedEngine === currentEngine && expectedMap != null && currentEngine.map === expectedMap) {
                AeroReplayTeamRestore(currentEngine, expectedMap, originalTeam, presentationOnly)
            } else null
        clear()
        return restore
    }

    private fun clear() {
        active = false
        engine = null
        map = null
        originalTeam = null
    }
}

/** Live observer perspective is presentation state, never the simulation's local player. */
internal object AeroLivePerspective {
    private var engine: GameEngine? = null
    private var map: NativeMap? = null
    private var team: Team? = null

    fun selected(current: GameEngine): Team? =
        if (engine === current && map === current.map &&
            io.github.rwpp.game.hud.isAeroLiveObserver(current)
        ) team else null

    fun clear(current: GameEngine) {
        if (engine !== current) return
        engine = null
        map = null
        team = null
    }

    fun select(current: GameEngine, selected: Team?) {
        engine = current
        map = current.map
        team = selected
    }
}

internal fun aeroPresentationTeam(engine: GameEngine): Team? =
    AeroLivePerspective.selected(engine) ?: engine.playerTeam

/** Always restore identity, including a failed native render. Pointer handling stays outside. */
internal inline fun <T> withAeroPresentationTeam(engine: GameEngine, draw: () -> T): T {
    // Native updates synchronize on this lock, including Android's separate render thread.
    return synchronized(engine.gameUpdateLock) {
        val original = engine.playerTeam
        val viewer = aeroPresentationTeam(engine)
        engine.playerTeam = viewer
        // Native terrain fog and unit visibility read playerTeam.fogGrid directly.
        // Present the combined ally-group grid for the duration of the draw only.
        val combinedFog = AeroViewerFog.grid(engine)
        val originalFog = viewer?.fogGrid
        if (viewer != null && combinedFog != null) viewer.fogGrid = combinedFog
        try {
            draw()
        } finally {
            if (viewer != null && combinedFog != null) viewer.fogGrid = originalFog
            engine.playerTeam = original
        }
    }
}

/** Invalidate native fog-rendered caches without changing the live simulation identity. */
internal fun setAeroReplayPerspective(engine: GameEngine, team: Team?) {
    if (aeroPresentationTeam(engine) === team) return
    AeroViewerFog.invalidate()
    engine.interfaceEngine.clearUnitSelection()
    if (io.github.rwpp.game.hud.isAeroLiveObserver(engine)) {
        AeroLivePerspective.select(engine, team)
    } else {
        engine.playerTeam = team
    }
    engine.minimap.fogRedrawPending = true
    engine.map?.let { map ->
        map.resetSmoothFogCaches()
        map.invalidateLayerBuffers()
    }
}

/** A live observer session never owns the simulation player, including after disconnect. */
internal fun restoreAeroReplayPerspective(restore: AeroReplayTeamRestore) {
    val engine = restore.engine
    if (engine.map !== restore.map) return
    if (restore.presentationOnly) {
        AeroLivePerspective.clear(engine)
        engine.minimap.fogRedrawPending = true
        engine.map?.resetSmoothFogCaches()
        engine.map?.invalidateLayerBuffers()
    } else {
        setAeroReplayPerspective(engine, restore.team)
    }
}
