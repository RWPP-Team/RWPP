/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.replay.aeroPresentationTeam
import com.corrodinggames.rts.game.map.Map as NativeMap

/** Identity only: native objects are read while their engine's gameUpdateLock is held. */
internal data class NativeWorldFrame(val engine: GameEngine, val map: NativeMap, val viewer: Team) {
    fun sameWorld(other: NativeWorldFrame): Boolean =
        engine === other.engine && map === other.map && viewer === other.viewer
}

/** Never invoke native visibility during loading/unload or through an obsolete engine reference. */
internal fun nativeWorldFrame(engine: GameEngine?): NativeWorldFrame? {
    if (engine == null || GameEngine.getInstance() !== engine || !engine.gameLoopActive) return null
    val map = engine.map ?: return null
    if (!map.mapLoaded) return null
    val viewer = aeroPresentationTeam(engine) ?: return null
    return NativeWorldFrame(engine, map, viewer)
}

/** Preserve native visibility rules, but never let an unready fog grid reveal or crash a unit. */
internal fun nativeUnitVisible(
    world: NativeWorldFrame, unit: com.corrodinggames.rts.game.units.Unit,
    viewer: Team = world.viewer
): Boolean {
    val engine = world.engine
    return GameEngine.getInstance() === engine && synchronized(engine.gameUpdateLock) {
        val map = world.map
        if (aeroPresentationTeam(engine) !== world.viewer || engine.map !== map || !engine.gameLoopActive || !map.mapLoaded || unit.team == null || unit.removed ||
            !unit.x.isFinite() || !unit.y.isFinite()
        ) return@synchronized false
        val fog = viewer.fogGrid
        if ((unit.team !== viewer || unit.attachedToUnit != null) && map.fogOfWarEnabled && fog != null) {
            map.pixelToTileCoords(unit.x, unit.y)
            val x = map.fogRevealTileX
            val y = map.fogRevealTileY
            if (map.isTileInBounds(x, y) && fog.getOrNull(x)?.getOrNull(y) == null) return@synchronized false
        }
        unit.isVisibleToTeam(viewer)
    }
}
