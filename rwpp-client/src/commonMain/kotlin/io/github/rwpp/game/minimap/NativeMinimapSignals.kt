/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.minimap

import com.corrodinggames.rts.game.Projectile
import com.corrodinggames.rts.gameFramework.CommonUtils
import com.corrodinggames.rts.gameFramework.gui.MinimapHandler
import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerNotificationType
import io.github.rwpp.game.nativeaccess.NativeMinimapAnimationAccess
import io.github.rwpp.game.nativeaccess.NativeMinimapNotificationAccess
import kotlin.math.roundToInt

internal data class MinimapNativeAlert(
    val x: Float,
    val y: Float,
    val type: MinimapHandlerNotificationType,
    val scaleX: Float,
    val scaleY: Float,
)

internal data class MinimapProjectileMarker(val x: Float, val y: Float, val radiusFraction: Float, val color: Int)

internal data class MinimapNativeSnapshot(
    val alerts: List<MinimapNativeAlert> = emptyList(),
    val projectileMarkers: List<MinimapProjectileMarker> = emptyList(),
    val notificationPulseAngle: Float = 0f,
    val borderFlashColor: Int? = null,
)

internal data class MinimapNotificationFade(val scaleX: Float, val scaleY: Float)

/** Keeps the render-only animation updates identical to MinimapHandler.render(delta). */
internal fun advanceNativeMinimapAngle(angle: Float, delta: Float, speed: Float): Float {
    var next = angle + speed * delta
    if (next > 180f) next -= 180f
    return next
}

/** Native notifications first shrink horizontally, then vertically once scaleX reaches zero. */
internal fun advanceNativeMinimapNotification(
    scaleX: Float,
    scaleY: Float,
    delta: Float,
): MinimapNotificationFade {
    val nextX = CommonUtils.applyDeadzone(scaleX, 0.04f * delta)
    val nextY = if (nextX == 0f) CommonUtils.applyDeadzone(scaleY, 0.005f * delta) else scaleY
    return MinimapNotificationFade(nextX, nextY)
}

internal fun minimapAlertDrawScale(type: MinimapHandlerNotificationType, scaleX: Float): Float =
    scaleX.coerceIn(if (type == MinimapHandlerNotificationType.unit) 0.03f else 0.05f, 1f)

/** Alert border colors follow the native minimap pulse; unit hits also flash red per the requested Aero behavior. */
internal fun minimapBorderFlashColor(
    types: Iterable<MinimapHandlerNotificationType>,
    nukeActive: Boolean,
    angle: Float,
): Int? {
    val hasDamage = types.any { it != MinimapHandlerNotificationType.message }
    val hasMessage = types.any { it == MinimapHandlerNotificationType.message }
    if (!nukeActive && !hasDamage && !hasMessage) return null
    val pulse = CommonUtils.sin(angle)
    return when {
        nukeActive -> android.graphics.Color.argb(255, 0, (pulse * 230f).toInt(), 0)
        hasDamage -> android.graphics.Color.argb(255, (pulse * 230f).toInt(), 0, 0)
        else -> android.graphics.Color.argb(255, 12, (pulse * 220f).toInt(), (pulse * 220f).toInt())
    }
}

internal fun minimapAlertColor(type: MinimapHandlerNotificationType, angle: Float): Int {
    if (type == MinimapHandlerNotificationType.message) return android.graphics.Color.argb(200, 12, 227, 219)
    val alphaAndRed = (50f + CommonUtils.sin(angle) * 190f).toInt()
    return android.graphics.Color.argb(alphaAndRed, alphaAndRed, 0, 0)
}

/** Nuke circles keep the owning team's color and pulse on the native minimap render angle. */
internal fun minimapNukeMarkerColor(teamColor: Int, angle: Float): Int {
    val alpha = (102f + CommonUtils.sin(angle) * 153f).roundToInt().coerceIn(102, 255)
    return (teamColor and 0x00ffffff) or (alpha shl 24)
}

/** Called only after the native minimap render is cancelled; advances native render-only state once per frame. */
internal object AeroMinimapSignals {
    private val emptySnapshot = MinimapNativeSnapshot()

    @Volatile
    private var latest = emptySnapshot
    private var owner: MinimapHandler? = null

    @Synchronized
    fun capture(minimap: MinimapHandler, delta: Float) {
        if (owner !== minimap) {
            latest = emptySnapshot
            owner = minimap
        }

        val animation = minimap as NativeMinimapAnimationAccess
        val notifications = minimap.damageNotifications
        val notificationTypes = ArrayList<MinimapHandlerNotificationType>(notifications.size)
        val alerts = ArrayList<MinimapNativeAlert>(notifications.size)
        val iterator = notifications.iterator()
        // render(F) is cancelled at HEAD, so its two render-only angles advance here exactly once.
        val nextPulseAngle = advanceNativeMinimapAngle(animation.rwppNotificationPulseAngle(), delta, 6f)
        animation.rwppNotificationPulseAngle(nextPulseAngle)

        while (iterator.hasNext()) {
            val access = iterator.next() as? NativeMinimapNotificationAccess ?: continue
            val type = access.rwppSignalType()
            val scaleX = access.rwppSignalScaleX()
            val scaleY = access.rwppSignalScaleY()
            notificationTypes += type
            // Snapshot pre-decay values, matching the native renderer's draw-then-decay order.
            alerts += MinimapNativeAlert(
                access.rwppSignalX().toFloat(), access.rwppSignalY().toFloat(), type, scaleX, scaleY
            )

            val faded = advanceNativeMinimapNotification(scaleX, scaleY, delta)
            access.rwppSignalScaleX(faded.scaleX)
            if (faded.scaleX == 0f) {
                access.rwppSignalScaleY(faded.scaleY)
                if (faded.scaleY == 0f) iterator.remove()
            }
        }

        val projectileMarkers = ArrayList<MinimapProjectileMarker>()
        var nukeActive = false
        val projectiles = Projectile.allProjectiles
        val projectileItems = projectiles.items()
        val nativeMinimapSize = minOf(minimap.width, minimap.height).takeIf { it > 0f } ?: 120f
        for (index in projectiles.indices) {
            val projectile = projectileItems[index] as? Projectile ?: continue
            if (projectile.nukeWeapon) nukeActive = true
            val interceptedNuke = projectile.interceptTargetProjectile?.nukeWeapon == true
            if ((projectile.nukeWeapon || interceptedNuke) && projectile.sourceUnit != null) {
                val radius = if (projectile.nukeWeapon) 4f else 2f
                projectileMarkers += MinimapProjectileMarker(
                    projectile.x,
                    projectile.y,
                    radius / nativeMinimapSize,
                    projectile.sourceUnit.team.teamColorPaint.color
                )
            }
        }

        val currentBorderColor = if (notificationTypes.isNotEmpty() || nukeActive) {
            val nextBorderAngle = advanceNativeMinimapAngle(animation.rwppBorderFlashAngle(), delta, 5f)
            animation.rwppBorderFlashAngle(nextBorderAngle)
            minimapBorderFlashColor(notificationTypes, nukeActive, nextBorderAngle)
        } else null
        latest = MinimapNativeSnapshot(alerts, projectileMarkers, nextPulseAngle, currentBorderColor)
    }

    @Synchronized
    fun snapshot(): MinimapNativeSnapshot = latest

    @Synchronized
    fun clear() {
        if (owner != null) {
            owner = null
            latest = emptySnapshot
        }
    }
}

