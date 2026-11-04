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
import com.corrodinggames.rts.gameFramework.gui.PromptResult
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine

internal enum class AeroPlacementPromptIcon { CONFIRM, REPEAT, CANCEL }

internal fun aeroPlacementPromptIcon(result: PromptResult): AeroPlacementPromptIcon = when (result) {
    PromptResult.yes -> AeroPlacementPromptIcon.CONFIRM
    PromptResult.more -> AeroPlacementPromptIcon.REPEAT
    PromptResult.no -> AeroPlacementPromptIcon.CANCEL
}

/** Draws Aero-styled replacements at the native prompt button coordinates. */
internal object AeroPlacementPromptPainter {
    private val rect = Rect()
    private val outlineRect = Rect()
    private val background = Paint().apply {
        style = Paint.Style.FILL
        setARGB(232, 5, 17, 30)
    }
    private val border = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val glyph = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    fun draw(graphics: GraphicsEngine, kind: AeroPlacementPromptIcon, x: Float, y: Float, scale: Float) {
        // InterfaceEngine truncates the same expression when it creates the native hit rectangle.
        val size = (100f * scale).toInt().coerceAtLeast(1).toFloat()
        val right = x + size
        val bottom = y + size
        rect.set(x.toInt(), y.toInt(), right.toInt(), bottom.toInt())
        graphics.drawRect(rect, background)

        val accent = when (kind) {
            AeroPlacementPromptIcon.CONFIRM -> Color.rgb(93, 230, 174)
            AeroPlacementPromptIcon.REPEAT -> Color.rgb(82, 202, 245)
            AeroPlacementPromptIcon.CANCEL -> Color.rgb(255, 104, 125)
        }
        border.color = accent
        border.alpha = 230
        border.strokeWidth = (1.6f * scale).coerceAtLeast(1f)
        val inset = kotlin.math.ceil(border.strokeWidth / 2f).toInt()
        outlineRect.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        graphics.drawRect(outlineRect, border)
        glyph.color = accent
        glyph.alpha = 255
        glyph.strokeWidth = (5f * scale).coerceAtLeast(2f)
        val centerX = x + size * 0.5f
        val centerY = y + size * 0.48f
        val arm = size * 0.18f
        when (kind) {
            AeroPlacementPromptIcon.CONFIRM -> {
                graphics.drawLine(centerX - arm, centerY, centerX - arm * 0.25f, centerY + arm * 0.72f, glyph)
                graphics.drawLine(
                    centerX - arm * 0.25f,
                    centerY + arm * 0.72f,
                    centerX + arm,
                    centerY - arm * 0.72f,
                    glyph
                )
            }

            AeroPlacementPromptIcon.REPEAT -> {
                val boxSize = size * 0.24f
                val first = Rect(
                    (centerX - boxSize * 0.72f).toInt(), (centerY - boxSize * 0.55f).toInt(),
                    (centerX + boxSize * 0.28f).toInt(), (centerY + boxSize * 0.45f).toInt()
                )
                val second = Rect(
                    (centerX - boxSize * 0.28f).toInt(), (centerY - boxSize * 0.05f).toInt(),
                    (centerX + boxSize * 0.72f).toInt(), (centerY + boxSize * 0.95f).toInt()
                )
                graphics.drawRect(first, glyph)
                graphics.drawRect(second, glyph)
            }

            AeroPlacementPromptIcon.CANCEL -> {
                graphics.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, glyph)
                graphics.drawLine(centerX + arm, centerY - arm, centerX - arm, centerY + arm, glyph)
            }
        }
        // Thin top edge is the shared Aero accent that ties the native placement controls to the HUD.
        graphics.drawLine(x + size * 0.17f, y + size * 0.04f, x + size * 0.83f, y + size * 0.04f, glyph)
    }
}
