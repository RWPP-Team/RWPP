/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.graphics.ImageBitmap

/** Platform copies completed native pixels before releasing its render target. */
internal expect fun withNativeHudCanvas(draw: (com.corrodinggames.rts.gameFramework.render.GraphicsEngine) -> Unit): ImageBitmap

internal fun renderNativeHudIcon(request: HudIconRequest): ImageBitmap = withNativeHudCanvas { canvas ->
    val key = request.key
    val icon = request.icon
    if (icon != null) {
        require(key.sourceWidth > 0 && key.sourceHeight > 0) { "Empty native icon crop" }
        val scale = minOf(76.8f / key.sourceWidth, 76.8f / key.sourceHeight)
        val w = key.sourceWidth * scale
        val h = key.sourceHeight * scale
        canvas.drawImage(
            icon,
            android.graphics.Rect(key.left, key.top, key.left + key.sourceWidth, key.top + key.sourceHeight),
            android.graphics.RectF(48f - w / 2f, 48f - h / 2f, 48f + w / 2f, 48f + h / 2f), null
        )
    } else if (request.type != null) {
        com.corrodinggames.rts.game.units.UnitType.drawUnit(
            requireNotNull(request.type),
            48f, 48f, 0f, 0f, request.team, 38.4f, 81.6f, false, false, key.techLevel, request.shown
        )
    }
    request.overlay?.let { overlay ->
        require(key.overlayWidth > 0 && key.overlayHeight > 0) { "Empty native overlay crop" }
        val scale = minOf(76.8f / key.overlayWidth, 76.8f / key.overlayHeight)
        val w = key.overlayWidth * scale
        val h = key.overlayHeight * scale
        canvas.drawImage(
            overlay, android.graphics.Rect(
                key.overlayLeft, key.overlayTop,
                key.overlayLeft + key.overlayWidth, key.overlayTop + key.overlayHeight
            ),
            android.graphics.RectF(48f - w / 2f, 48f - h / 2f, 48f + w / 2f, 48f + h / 2f),
            android.graphics.Paint().apply { color = key.overlayColor })
    }
}
