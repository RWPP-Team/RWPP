/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Rect
import android.graphics.RectF
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture


/** Native placement, altitude, recoil and turret transforms are shared by both material backends. */
internal object WorldSceneSprites {
    fun visible(engine: GameEngine, draw: (OrderableUnit) -> Unit) {
        // Retain the native fog gate for material drawing, including perspective transitions.
        // Camera-motion optimization skips the separate signature prepass instead.
        val objects = (engine as NativeWorldAccess).rwppVisibleObjects()
        val items = objects.items()
        for (i in objects.indices) {
            val unit = items[i] as? OrderableUnit ?: continue
            if (unit.dead || unit.transportedBy != null || unit.attachedToUnit != null || unit.isVirtualUnit || !unit.isVisibleToTeam(
                    engine.playerTeam
                )
            ) continue
            draw(unit)
        }
    }

    fun masks(
        engine: GameEngine,
        inspectUnit: (OrderableUnit) -> Unit = {},
        draw: (BitmapOrTexture, Rect, RectF, Float, Float, Float, Float, Boolean) -> Unit
    ) {
        val source = Rect();
        val bounds = RectF()
        visible(engine) { unit ->
            inspectUnit(unit)
            unit.bodyImage?.let { texture ->
                val frame = unit.getSpriteFrameRect(false)
                source.set(frame.left, frame.top, frame.right, frame.bottom)
                if (source.width() > 0 && source.height() > 0) {
                    val offset = unit.turretDrawOffset
                    unitMaterialBodyBounds(unit.bodyScreenRect, offset.x, offset.y, unit.height, bounds)
                    draw(
                        texture,
                        source,
                        bounds,
                        unit.getDrawAngle(false),
                        bounds.centerX(),
                        bounds.centerY(),
                        1f,
                        false
                    )
                }
            }
            for (index in 0 until unit.turretCount) {
                val texture = unit.getTurretImage(index) ?: continue
                val point = unit.getTurretPosition3F(index)
                val x = point.x - engine.cameraRenderX
                val y = point.y - engine.cameraRenderY - unit.height - point.z
                source.set(0, 0, texture.width, texture.height)
                val left = x - texture.halfWidthFloat - unit.getTurretImageDrawOffsetX(index)
                val top = y - texture.halfHeightFloat - unit.getTurretImageDrawOffsetY(index)
                bounds.set(left, top, left + texture.width, top + texture.height)
                draw(
                    texture,
                    source,
                    bounds,
                    unit.turrets[index].angle + 90f,
                    x,
                    y,
                    unit.getTurretDrawScale(index),
                    true
                )
            }
        }
    }
}

internal fun worldProjectedOffset(radius: Float, height: Float): Pair<Float, Float> {
    val travel =
        (radius.coerceIn(2f, 60f) * 0.08f + 1.5f + height.coerceAtLeast(0f) * (0.55f / 0.82f)).coerceAtMost(32f)
    return travel to travel
}

internal fun worldShadowSoftness(height: Float) = (height.coerceAtLeast(0f) / 40f).coerceIn(0f, 1f)

internal expect fun replacesNativeProjectedShadow(unit: OrderableUnit): Boolean
