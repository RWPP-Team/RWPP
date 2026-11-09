/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.camera

import com.corrodinggames.rts.gameFramework.GameEngine

/** Match native centering on small maps, while allowing a bounded margin on either side. */
internal fun overscrollCameraAxis(position: Float, mapExtent: Float, viewportExtent: Float, margin: Float): Float {
    val extent = (mapExtent - viewportExtent).coerceAtLeast(-viewportExtent)
    val minimum = if (extent < 0f) extent / 2f else 0f
    val maximum = if (extent < 0f) minimum else extent
    val distance = if (margin.isFinite()) margin.coerceIn(0f, 9600f) else 0f
    return (if (position.isFinite()) position else minimum).coerceIn(minimum - distance, maximum + distance)
}

internal fun clampOverscrollCamera(engine: GameEngine, marginPixels: Float) {
    val map = engine.map ?: return
    val zoom = engine.zoomScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val margin = marginPixels.coerceIn(0f, 800f) / zoom
    val x = overscrollCameraAxis(engine.cameraWorldX, map.pixelWidth, engine.gameAreaWorldWidth, margin)
    val y = overscrollCameraAxis(engine.cameraWorldY, map.pixelHeight, engine.viewHeight, margin)
    engine.cameraWasClamped = x != engine.cameraWorldX || y != engine.cameraWorldY
    engine.setCamera(x, y)
}
