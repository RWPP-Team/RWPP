/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.graphics.ImageBitmap
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.actions.SpecialAction
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.game.units.Unit as GameUnit

internal expect fun hudImageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap
internal expect fun prepareHudTexture(texture: BitmapOrTexture)

internal expect fun readHudTexturePixels(
    texture: BitmapOrTexture, left: Int, top: Int,
    sourceWidth: Int, sourceHeight: Int, width: Int, height: Int
): IntArray

/** Immutable native inputs; Compose never reads live actions or changes the game's renderer. */
internal data class HudIconKey(
    val actionClass: Class<*>, val actionId: String, val typeIdentity: Int,
    val techLevel: Int, val teamColor: Int, val teamSlot: Int, val shownIdentity: Int,
    val iconId: Int, val iconVersion: Int, val left: Int, val top: Int,
    val sourceWidth: Int, val sourceHeight: Int, val overlayId: Int, val overlayVersion: Int,
    val overlayLeft: Int, val overlayTop: Int, val overlayWidth: Int, val overlayHeight: Int,
    val overlayColor: Int,
)

internal data class HudIconRequest(
    val key: HudIconKey, val type: UnitTypeInterface?,
    val shown: GameUnit?, val team: Team,
    val icon: BitmapOrTexture?, val overlay: BitmapOrTexture?,
)

internal fun SpecialAction.hudIconRequest(target: GameUnit): HudIconRequest {
    val shown = getUnitShownInUI(target)
    val type = shown?.unitTypeInterface ?: unitType
    val texture = icon ?: type?.buildIcon
    val crop = iconRect
    val left = (crop?.left ?: 0).coerceIn(0, ((texture?.width ?: 1) - 1).coerceAtLeast(0))
    val top = (crop?.top ?: 0).coerceIn(0, ((texture?.height ?: 1) - 1).coerceAtLeast(0))
    val width = (crop?.width() ?: texture?.width ?: 0).coerceIn(0, ((texture?.width ?: 0) - left).coerceAtLeast(0))
    val height = (crop?.height() ?: texture?.height ?: 0).coerceIn(0, ((texture?.height ?: 0) - top).coerceAtLeast(0))
    val overlay = getOverlayIcon(target)
    val overlayLeft = (crop?.left ?: 0).coerceIn(0, ((overlay?.width ?: 1) - 1).coerceAtLeast(0))
    val overlayTop = (crop?.top ?: 0).coerceIn(0, ((overlay?.height ?: 1) - 1).coerceAtLeast(0))
    val overlayWidth =
        (crop?.width() ?: overlay?.width ?: 0).coerceIn(0, ((overlay?.width ?: 0) - overlayLeft).coerceAtLeast(0))
    val overlayHeight =
        (crop?.height() ?: overlay?.height ?: 0).coerceIn(0, ((overlay?.height ?: 0) - overlayTop).coerceAtLeast(0))
    val team = target.team
    return HudIconRequest(
        HudIconKey(
            javaClass, actionIdString, System.identityHashCode(type),
            techLevel, team.color, team.getDisplaySlot(), System.identityHashCode(shown),
            texture?.id ?: -1, texture?.version ?: 0, left, top, width, height,
            overlay?.id ?: -1, overlay?.version ?: 0,
            overlayLeft, overlayTop, overlayWidth, overlayHeight, buttonColor
        ), type, shown, team, texture, overlay
    )
}

/** Native unit icon request used by unit type pickers outside the in-game action bar. */
internal fun UnitTypeInterface.hudIconRequest(team: Team): HudIconRequest {
    val texture = buildIcon
    val width = texture?.width ?: 0
    val height = texture?.height ?: 0
    return HudIconRequest(
        HudIconKey(
            javaClass, internalName, System.identityHashCode(this),
            1, team.color, team.getDisplaySlot(), 0,
            texture?.id ?: -1, texture?.version ?: 0, 0, 0, width, height,
            -1, 0, 0, 0, 0, 0, overlayColor = 0,
        ),
        this, null, team, texture, null,
    )
}

/** One producer at the native drawAll boundary under renderLock; consumers only enqueue/read. */
internal object ActionImages {
    private val lock = Any()
    private val images = LinkedHashMap<HudIconKey, ImageBitmap?>()
    private val inFlight = HashSet<HudIconKey>()
    private val pending = LinkedHashMap<HudIconKey, HudIconRequest>()
    private val visiblePending = LinkedHashMap<HudIconKey, HudIconRequest>()
    private var generation = 0L

    @JvmOverloads
    fun load(action: SpecialAction, target: GameUnit, visible: Boolean = false): ImageBitmap? =
        request(action.hudIconRequest(target), visible)

    internal fun loadUnitType(type: UnitTypeInterface, team: Team, visible: Boolean = false): ImageBitmap? =
        request(type.hudIconRequest(team), visible)

    internal fun request(request: HudIconRequest, visible: Boolean = false): ImageBitmap? = synchronized(lock) {
        val key = request.key
        if (images.containsKey(key)) {
            // LRU rather than insertion order: repeatedly displayed icons are the last to leave.
            val image = images.remove(key)
            images[key] = image
            return@synchronized image
        }
        if (request.icon == null && request.type == null && request.overlay == null) return@synchronized null
        if (key !in inFlight) {
            if (visible) {
                pending.remove(key)
                visiblePending.putIfAbsent(key, request)
            } else if (key !in visiblePending) pending.putIfAbsent(key, request)
        }
        while (pending.size + visiblePending.size > 512) {
            if (pending.isNotEmpty()) pending.remove(pending.keys.first())
            else visiblePending.remove(visiblePending.keys.first())
        }
        null
    }

    internal fun renderPending(render: (HudIconRequest) -> ImageBitmap = ::renderNativeHudIcon) {
        repeat(4) {
            val (request, requestGeneration) = synchronized(lock) {
                val queue = visiblePending.ifEmpty { pending }
                val key = queue.keys.firstOrNull() ?: return
                inFlight.add(key)
                queue.remove(key)!! to generation
            }
            val image = try {
                // Transparent pixels are valid native output (for example modularSpider_emptySlot).
                render(request)
            } catch (failure: Exception) {
                io.github.rwpp.api.logging.logger().warn(
                    "Native HUD image failed: ${request.key.actionId} (${request.key})", failure
                )
                null
            }
            synchronized(lock) {
                if (requestGeneration != generation) return@synchronized
                // Publication follows drawing, flushing and copying. Failure is terminal for these
                // inputs, never a timer/frame retry. Native version changes produce a new recipe.
                inFlight.remove(request.key)
                images[request.key] = image
                while (images.size > 512) images.remove(images.keys.first())
            }
        }
    }

    internal fun clear() = synchronized(lock) {
        generation++
        pending.clear(); visiblePending.clear(); images.clear(); inFlight.clear()
    }
}
