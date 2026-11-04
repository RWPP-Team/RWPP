/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.game.units.Unit as GameUnit

private val collisionPaint = Paint().apply {
    color = android.graphics.Color.argb(220, 255, 210, 75)
    style = Paint.Style.STROKE
    strokeWidth = 1.5f
}
private val buildingFootprint = RectF()

internal fun collisionBoundsShouldDraw(
    collisionEnabled: Boolean,
    dead: Boolean,
    transported: Boolean,
    visibleToViewer: Boolean,
): Boolean = collisionEnabled && !dead && !transported && visibleToViewer

/** Collision is a ground-plane circle; never reveal units hidden by native fog. */
internal fun drawCollisionBounds(unit: GameUnit, graphics: GraphicsEngine) {
    val engine = GameEngine.getInstance()
    val viewer = engine.playerTeam ?: return
    if (!collisionBoundsShouldDraw(
            collisionEnabled = unit.collisionEnabled,
            dead = unit.dead,
            transported = unit.transportedBy != null,
            visibleToViewer = unit.isVisibleToTeam(viewer),
        )
    ) return
    if (unit.radius.isFinite() && unit.radius > 0f) {
        graphics.drawCircle(unit.x - engine.cameraRenderX, unit.y - engine.cameraRenderY, unit.radius, collisionPaint)
    }
    if (unit.isBuilding && engine.map != null) {
        unit.getMinimapRect(engine.map, buildingFootprint)
        buildingFootprint.offset(-engine.cameraRenderX, -engine.cameraRenderY)
        graphics.drawRect(buildingFootprint, collisionPaint)
    }
}
