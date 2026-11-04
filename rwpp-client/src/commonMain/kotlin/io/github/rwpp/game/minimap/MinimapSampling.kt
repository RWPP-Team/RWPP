/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.minimap

import com.corrodinggames.rts.game.map.MapTile
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.replay.aeroPresentationTeam
import io.github.rwpp.graphics.readHudTexturePixels
import com.corrodinggames.rts.game.map.Map as GameMap
import com.corrodinggames.rts.game.units.Unit as GameUnit

internal const val MINIMAP_TERRAIN_RESOLUTION = 128

internal const val MINIMAP_PLAYER_UNIT_COLOR = 0xff55d879.toInt()
internal const val MINIMAP_ALLIED_UNIT_COLOR = 0xffffd45c.toInt()
internal const val MINIMAP_ENEMY_UNIT_COLOR = 0xffff6262.toInt()
internal const val MINIMAP_RESOURCE_COLOR = 0xffffffff.toInt()
internal const val MINIMAP_RESOURCE_RADIUS_DP = 0.8f

internal data class MinimapDot(
    val x: Float,
    val y: Float,
    val color: Int,
    val radiusDp: Float,
    val selected: Boolean,
)

/** Immutable sample handed to Compose; arrays/lists are replaced, never changed after publication. */
internal data class MinimapFrameData(
    val terrainPixels: IntArray,
    val terrainWidth: Int,
    val terrainHeight: Int,
    val worldWidth: Float,
    val worldHeight: Float,
    val dots: List<MinimapDot>,
    val native: MinimapNativeSnapshot,
)

internal fun minimapFogColor(color: Int, fog: Int): Int {
    if (fog >= 10) return 0xff000000.toInt()
    if (fog < 5) return color
    return 0xff000000.toInt() or (((color ushr 16 and 255) * 0.4f).toInt() shl 16) or
            (((color ushr 8 and 255) * 0.4f).toInt() shl 8) or ((color and 255) * 0.4f).toInt()
}

/** Returns the previous immutable pixels when fog output is unchanged, or a fresh array on change. */
internal fun updateMinimapFogPixels(
    terrainPixels: IntArray,
    previousPixels: IntArray,
    resolution: Int,
    mapWidthInTiles: Int,
    mapHeightInTiles: Int,
    fog: Array<ByteArray>?,
): IntArray {
    fun colorAt(index: Int): Int {
        val x = index % resolution * mapWidthInTiles / resolution
        val y = index / resolution * mapHeightInTiles / resolution
        val level = fog?.getOrNull(x)?.getOrNull(y)?.toInt() ?: 0
        return minimapFogColor(terrainPixels[index], level)
    }

    val changed = previousPixels.size != terrainPixels.size ||
            terrainPixels.indices.any { index -> previousPixels[index] != colorAt(index) }
    if (!changed) return previousPixels

    return IntArray(terrainPixels.size) { index -> colorAt(index) }
}

internal fun minimapUnitColor(
    playerColor: Int,
    isPlayerUnit: Boolean,
    isEnemy: Boolean,
    usePlayerColors: Boolean,
): Int = when {
    usePlayerColors -> playerColor
    isPlayerUnit -> MINIMAP_PLAYER_UNIT_COLOR
    isEnemy -> MINIMAP_ENEMY_UNIT_COLOR
    else -> MINIMAP_ALLIED_UNIT_COLOR
}

internal fun minimapUnitRadiusDp(isBuilding: Boolean): Float = if (isBuilding) 1.25f else 0.9f

internal fun minimapResourceDots(
    tilePositions: Iterable<Pair<Int, Int>>,
    tileWidth: Int,
    tileHeight: Int,
): List<MinimapDot> = tilePositions.map { (tileX, tileY) ->
    MinimapDot(
        (tileX + 0.5f) * tileWidth,
        (tileY + 0.5f) * tileHeight,
        MINIMAP_RESOURCE_COLOR,
        MINIMAP_RESOURCE_RADIUS_DP,
        false,
    )
}

internal fun minimapUnitMarkerVisible(
    dead: Boolean,
    hasTeam: Boolean,
    transported: Boolean,
    visibleToPlayer: Boolean,
    shouldShowOnMinimap: Boolean,
    virtualUnit: Boolean,
): Boolean = !dead && hasTeam && !transported && visibleToPlayer && shouldShowOnMinimap && !virtualUnit

/** A tap orders selected units on touch; mouse primary clicks retain camera navigation. */
internal fun minimapTapOrders(touch: Boolean, secondary: Boolean, controllableSelection: Boolean): Boolean =
    controllableSelection && (touch || secondary)

internal class MinimapRefreshSchedule {
    private var lastUnits = Long.MIN_VALUE
    private var lastFog = Long.MIN_VALUE

    fun unitsDue(now: Long): Boolean =
        (lastUnits == Long.MIN_VALUE || now - lastUnits >= 100L).also { if (it) lastUnits = now }

    fun fogDue(now: Long): Boolean =
        (lastFog == Long.MIN_VALUE || now - lastFog >= 200L).also { if (it) lastFog = now }
}

/** Samples map textures, fog, resource sites and units on the game update thread. */
internal class NativeMinimapSampler {
    private var map: GameMap? = null
    private var terrain = IntArray(0)
    private var publishedTerrainPixels = IntArray(0)
    private val resolution = MINIMAP_TERRAIN_RESOLUTION

    private fun terrainColor(tile: MapTile?, colors: MutableMap<MapTile, Int>): Int {
        if (tile == null) return 0xff263638.toInt()
        return colors.getOrPut(tile) {
            runCatching {
                val texture = tile.tileSet.tilesetBitmap.loadedImage
                val rect = tile.tileSet.getCachedTileRect(tile.tileIndex)
                // Average a 3 × 3 patch; each unique terrain tile is read once per map.
                var red = 0
                var green = 0
                var blue = 0
                val sampled = readHudTexturePixels(texture, rect.left, rect.top, rect.width(), rect.height(), 3, 3)
                sampled.forEach { color ->
                    red += color ushr 16 and 255
                    green += color ushr 8 and 255
                    blue += color and 255
                }
                0xff000000.toInt() or (red / 9 shl 16) or (green / 9 shl 8) or (blue / 9)
            }.getOrElse {
                when {
                    tile.water -> 0xff28567e.toInt()
                    tile.lava -> 0xff9c452c.toInt()
                    tile.cliff -> 0xff696457.toInt()
                    tile.trees -> 0xff294c37.toInt()
                    else -> 0xff5d7049.toInt()
                }
            }
        }
    }

    fun sample(engine: GameEngine, updateFog: Boolean, usePlayerColors: Boolean): MinimapFrameData? {
        val current = engine.map ?: return null
        if (current.mapWidthInTiles <= 0 || current.mapHeightInTiles <= 0 || current.groundLayer == null) return null
        if (map !== current) {
            val colors = HashMap<MapTile, Int>()
            terrain = IntArray(resolution * resolution) { index ->
                terrainColor(
                    current.getGroundTile(
                        index % resolution * current.mapWidthInTiles / resolution,
                        index / resolution * current.mapHeightInTiles / resolution,
                    ),
                    colors,
                )
            }
            map = current
            // Force a new immutable fog image on the next sample, even if the map has equal dimensions.
            publishedTerrainPixels = IntArray(0)
        }

        val playerTeam = aeroPresentationTeam(engine)
        val fog = if (current.fogOfWarEnabled) playerTeam?.fogGrid else null
        if (publishedTerrainPixels.size != terrain.size || updateFog) {
            publishedTerrainPixels = updateMinimapFogPixels(
                terrain,
                publishedTerrainPixels,
                resolution,
                current.mapWidthInTiles,
                current.mapHeightInTiles,
                fog,
            )
        }

        val resourceDots = minimapResourceDots(
            current.resourcePoolPoints.filterIsInstance<android.graphics.Point>().map { it.x to it.y },
            current.tileWidth,
            current.tileHeight,
        )
        val dots = ArrayList<MinimapDot>(resourceDots.size + GameUnit.unitList.size)
        dots.addAll(resourceDots)
        for (nativeUnit in GameUnit.unitList) {
            val unit = nativeUnit as? GameUnit ?: continue
            if (!minimapUnitMarkerVisible(
                    dead = unit.dead,
                    hasTeam = unit.team != null,
                    transported = unit.transportedBy != null,
                    visibleToPlayer = unit.isVisibleToTeam(playerTeam),
                    shouldShowOnMinimap = unit.shouldShowOnMinimap(),
                    virtualUnit = unit.isVirtualUnit,
                )
            ) continue
            val team = unit.team
            dots.add(
                MinimapDot(
                    unit.x,
                    unit.y,
                    minimapUnitColor(
                        team.color,
                        team === playerTeam,
                        playerTeam != null && team.isEnemy(playerTeam),
                        usePlayerColors,
                    ),
                    minimapUnitRadiusDp(unit.isBuilding),
                    unit.selected,
                )
            )
        }

        return MinimapFrameData(
            terrainPixels = publishedTerrainPixels,
            terrainWidth = resolution,
            terrainHeight = resolution,
            worldWidth = current.mapWidthInTiles * current.tileWidth.toFloat(),
            worldHeight = current.mapHeightInTiles * current.tileHeight.toFloat(),
            dots = dots,
            native = AeroMinimapSignals.snapshot(),
        )
    }
}

/** Called on the game dispatcher. Native movement preserves multiplayer queueing and world click effects. */
internal fun issueMinimapTap(x: Float, y: Float, touch: Boolean, secondary: Boolean): Boolean {
    if (!x.isFinite() || !y.isFinite()) return false
    val ui = GameEngine.getInstance().interfaceEngine
    val controllable = ui.selectedUnits.any { it is OrderableUnit && !it.dead && ui.canPlayerControlUnit(it) }
    if (!minimapTapOrders(touch, secondary, controllable)) return false
    ui.handleGroundTap(x, y, null)
    AeroMinimapOrders.add(x, y)
    return true
}
