/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.replay

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.hud.isAeroLiveObserver
import com.corrodinggames.rts.game.map.Map as NativeMap
import com.corrodinggames.rts.game.units.Unit as GameUnit

/**
 * Native shared vision is a write-time behaviour: Map.revealFogForTeams copies a
 * reveal into the other teams of an ally group, but it deliberately skips AI teams
 * (`!var12.isAI`). An ally controlled by the AI therefore never writes its vision
 * into the selected player's fogGrid, and the observer sees less than the player
 * did. These helpers combine the whole ally group for display.
 */
internal fun aeroViewerTeams(engine: GameEngine): List<Team> {
    val viewer = aeroPresentationTeam(engine) ?: return emptyList()
    if (viewer.isSpectator || viewer === Team.grayTeam) return emptyList()
    if (!engine.replayEngine.isPlayingReplay && !isAeroLiveObserver(engine)) return emptyList()
    return AeroViewerTeams.members(engine, viewer)
}

/** A unit is visible when any team in the selected player's ally group can see it. */
internal fun aeroUnitVisible(world: io.github.rwpp.graphics.NativeWorldFrame, unit: GameUnit): Boolean {
    val engine = world.engine
    val teams = aeroViewerTeams(engine)
    if (teams.isEmpty()) {
        return io.github.rwpp.graphics.nativeUnitVisible(world, unit)
    }
    return teams.any { io.github.rwpp.graphics.nativeUnitVisible(world, unit, it) }
}

/** Fog grid presented to native terrain, minimap and waypoint rendering. */
internal fun aeroViewerFogGrid(engine: GameEngine): Array<ByteArray>? =
    AeroViewerFog.grid(engine) ?: aeroPresentationTeam(engine)?.fogGrid

/**
 * Ally membership is stable inside a session, so the member list is rebuilt only when the
 * ally signature changes. aeroUnitVisible runs once per unit and frame, and the minimap
 * samples every visible unit, so a per-call allocation is not affordable.
 */
private object AeroViewerTeams {
    private var owner: GameEngine? = null
    private var mapIdentity = 0
    private var viewerId = Int.MIN_VALUE
    private var signature = 0
    private var members: List<Team> = emptyList()

    fun members(engine: GameEngine, viewer: Team): List<Team> {
        val signature = signature(viewer)
        val map = System.identityHashCode(engine.map)
        if (owner === engine && mapIdentity == map && viewerId == viewer.teamId && this.signature == signature) {
            return members
        }
        val built = ArrayList<Team>(4)
        for (id in 0 until Team.maxTeamId) {
            val team = Team.getTeam(id) ?: continue
            if (team !== viewer && (team.isSpectator || team.allyTeam != viewer.allyTeam)) continue
            built += team
        }
        owner = engine
        mapIdentity = map
        viewerId = viewer.teamId
        this.signature = signature
        members = built
        return built
    }

    /**
     * Allocation-free digest of the ally group. aeroUnitVisible runs once per unit and frame,
     * and the digest still notices a mid-session AI takeover of an allied slot.
     */
    private fun signature(viewer: Team): Int {
        var hash = viewer.allyTeam * 31 + 7
        for (id in 0 until Team.maxTeamId) {
            val team = Team.getTeam(id) ?: continue
            if (team !== viewer && (team.isSpectator || team.allyTeam != viewer.allyTeam)) continue
            hash = hash * 31 + team.teamId * 2 + (if (team.isAI) 1 else 0)
        }
        return hash
    }
}

internal object AeroViewerFog {
    private const val REFRESH_NANOS = 100_000_000L
    private const val MAX_FOG_TILES = 4_000_000L
    private const val MAX_MARKED_TILES = 2_048

    private var owner: GameEngine? = null
    private var mapIdentity = 0
    private var viewerId = Int.MIN_VALUE
    private var memberKey = ""
    private var cached: Array<ByteArray>? = null
    private var published: Array<ByteArray>? = null
    private var computedAt = 0L

    // Two buffers alternate so the previous grid stays readable while the next one is merged.
    private var spare: Array<ByteArray>? = null

    fun invalidate() {
        computedAt = 0L
        cached = null
        spare = null
        // The next grid belongs to another perspective, so the terrain buffers must
        // be rebuilt against it rather than compared with the previous one.
        published = null
    }

    fun grid(engine: GameEngine): Array<ByteArray>? {
        val teams = aeroViewerTeams(engine)
        if (teams.size < 2) return null
        val viewer = teams.firstOrNull { it === aeroPresentationTeam(engine) } ?: return null
        val now = System.nanoTime()
        val key = teams.joinToString(",") { it.teamId.toString() }
        val map = System.identityHashCode(engine.map)
        if (owner === engine && mapIdentity == map && viewerId == viewer.teamId &&
            memberKey == key && now - computedAt < REFRESH_NANOS
        ) return cached
        val grids = teams.map { it.fogGrid }
        val template = grids.firstOrNull { it != null && it.isNotEmpty() } ?: return null
        val width = template.size
        val height = template.maxOf { it.size }
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_FOG_TILES) return null
        val merged = buffer(width, height) ?: return null
        // A team with fog disabled sees the whole map, and so does the ally group.
        val anyFogDisabled = grids.any { it == null }
        for (x in 0 until width) {
            val column = merged[x]
            for (y in 0 until height) {
                if (anyFogDisabled) {
                    column[y] = 0
                    continue
                }
                var value: Byte = 5
                for (grid in grids) {
                    val cell = grid?.getOrNull(x)?.getOrNull(y) ?: continue
                    if (cell < value) value = cell
                }
                column[y] = value
            }
        }
        owner = engine
        mapIdentity = map
        viewerId = viewer.teamId
        memberKey = key
        cached = merged
        computedAt = now
        repaintTerrain(engine, merged)
        return merged
    }

    /** Reuses the two merge buffers; only allocates when the map size changes. */
    private fun buffer(width: Int, height: Int): Array<ByteArray>? {
        val previous = published
        if (previous != null && previous.size == width && previous[0].size == height) {
            val reuse = spare
            if (reuse == null || reuse.size != width || reuse[0].size != height) {
                spare = Array(width) { ByteArray(height) }
            }
            return spare
        }
        spare = Array(width) { ByteArray(height) }
        return spare
    }

    /**
     * Native marks the buffer entries dirty as each team's reveal lands, but only for
     * the player team. Ally reveals arrive through this merged grid, so the tiles that
     * changed have to be marked here or the fog overlay keeps the previous picture.
     */
    private fun repaintTerrain(engine: GameEngine, merged: Array<ByteArray>) {
        val buffer = NativeMap.mapLayerBuffer ?: return
        val previous = published
        // Publish before comparing: the merged array is the spare buffer, and the next
        // merge writes into the array that was published here.
        spare = previous
        published = merged
        if (previous == null || previous.size != merged.size) {
            buffer.invalidateAll()
            return
        }
        var marked = 0
        for (x in merged.indices) {
            val column = merged[x]
            val oldColumn = previous.getOrNull(x)
            if (oldColumn == null || oldColumn.size != column.size) {
                buffer.invalidateAll()
                return
            }
            for (y in column.indices) {
                if (column[y] == oldColumn[y]) continue
                buffer.markTileDirty(x, y, true)
                if (++marked > MAX_MARKED_TILES) {
                    buffer.invalidateAll()
                    return
                }
            }
        }
    }
}
