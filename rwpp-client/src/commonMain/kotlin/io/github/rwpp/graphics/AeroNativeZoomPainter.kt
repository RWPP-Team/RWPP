/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine

internal object AeroNativeZoomPainter {
    private val fill = Paint().apply { isAntiAlias = true }
    private val line = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val bounds = Rect()

    fun draw(graphics: GraphicsEngine, x: Float, y: Float, scale: Float, pressed: Boolean) {
        val w = 48f * scale
        val h = 54f * scale
        bounds.set(x.toInt(), y.toInt(), (x + w).toInt(), (y + h).toInt())
        fill.color = Color.argb(if (pressed) 240 else 205, 6, 21, 34)
        graphics.drawRect(bounds, fill)
        line.color = Color.argb(if (pressed) 255 else 205, 115, 228, 220)
        line.strokeWidth = 1.5f * scale
        graphics.drawRect(bounds, line)
        graphics.drawLine(x + 4f * scale, y + 3f * scale, x + w - 4f * scale, y + 3f * scale, line)
        val cx = x + w * 0.42f
        val cy = y + h * 0.43f
        val r = 10f * scale
        graphics.drawCircle(cx, cy, r, line)
        graphics.drawLine(cx + r * 0.70f, cy + r * 0.70f, cx + r * 1.45f, cy + r * 1.45f, line)
        graphics.drawLine(cx - r * 0.50f, cy, cx + r * 0.50f, cy, line)
        graphics.drawLine(cx, cy - r * 0.50f, cx, cy + r * 0.50f, line)
        val ax = x + w * 0.81f
        graphics.drawLine(ax - 3f * scale, y + 9f * scale, ax, y + 6f * scale, line)
        graphics.drawLine(ax, y + 6f * scale, ax + 3f * scale, y + 9f * scale, line)
        graphics.drawLine(ax - 3f * scale, y + h - 9f * scale, ax, y + h - 6f * scale, line)
        graphics.drawLine(ax, y + h - 6f * scale, ax + 3f * scale, y + h - 9f * scale, line)
    }
}
