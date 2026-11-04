/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Rect as SkiaRect

private val aeroRangeRuntimeEffect by lazy { RuntimeEffect.makeForShader(AERO_RANGE_MASK_SHADER) }

internal actual fun DrawScope.beginAeroRangeFrame(phase: Float) = Unit

internal actual fun releaseAeroRangeResources() = Unit

internal actual fun DrawScope.drawAeroRangeGroupMask(
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    worldPerPixel: Offset,
    phase: Float,
    detailed: Boolean,
) {
    if (group.circles.isEmpty()) return
    val bounds = aeroRangeMaskBounds(group, camera, worldToScreen, Size(size.width, size.height)) ?: return
    val builder = RuntimeShaderBuilder(aeroRangeRuntimeEffect).apply {
        uniform(
            "uWorldOrigin", camera.x + bounds.left * worldPerPixel.x,
            camera.y + bounds.top * worldPerPixel.y
        )
        uniform("uWorldPerPixel", worldPerPixel.x, worldPerPixel.y)
        uniform("uTint", group.color.red, group.color.green, group.color.blue, group.color.alpha)
        uniform("uPhase", phase)
        uniform("uDetailed", if (detailed) 1f else 0f)
    }
    try {
        val imageFilter = ImageFilter.makeRuntimeShader(
            runtimeShaderBuilder = builder,
            shaderName = "content",
            input = null,
        )
        try {
            val layerPaint = org.jetbrains.skia.Paint().apply { this.imageFilter = imageFilter }
            try {
                val canvas = drawContext.canvas.nativeCanvas
                val checkpoint = canvas.saveLayer(
                    SkiaRect.makeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom), layerPaint,
                )
                try {
                    val maskPaint = org.jetbrains.skia.Paint().apply {
                        isAntiAlias = true
                        color = 0xffffffff.toInt()
                    }
                    try {
                        group.circles.forEach { circle ->
                            val x = (circle.center.x - camera.x) * worldToScreen.x
                            val y = (circle.center.y - camera.y) * worldToScreen.y
                            val rx = circle.radius * worldToScreen.x
                            val ry = circle.radius * worldToScreen.y
                            canvas.drawOval(x - rx, y - ry, x + rx, y + ry, maskPaint)
                        }
                    } finally {
                        maskPaint.close()
                    }
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
            } finally {
                layerPaint.close()
            }
        } finally {
            imageFilter.close()
        }
    } finally {
        builder.close()
    }
}
