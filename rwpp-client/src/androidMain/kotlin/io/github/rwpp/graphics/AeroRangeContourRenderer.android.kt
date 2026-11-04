/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.*
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Paint as NativePaint

private data class AndroidRangeGroupKey(val team: Int, val kind: String)

private class AndroidRangeShaderState(val key: AndroidRangeGroupKey) {
    val shader = RuntimeShader(AERO_RANGE_MASK_SHADER)
    var effect: RenderEffect? = null
    val node = RenderNode("RWPP range mask ${key.team}:${key.kind}")
    val ovalBounds = RectF()
    var lastUsedFrame: Long = 0
    val maskPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = NativePaint.Style.FILL
    }
}

private class AndroidRangeFrameState {
    val shaderStates = HashMap<AndroidRangeGroupKey, AndroidRangeShaderState>()
    val fallback = AndroidRangeFallbackState()
    var frame: Long = 0

    fun beginFrame() {
        frame++
        val staleBefore = frame - 1
        val iterator = shaderStates.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state.lastUsedFrame < staleBefore) {
                // Canvas.drawRenderNode can retain a node reference until the frame is submitted.
                // Keep one unused frame before releasing its display list and shader effect.
                state.node.discardDisplayList()
                state.node.setRenderEffect(null)
                state.effect = null
                iterator.remove()
            }
        }
    }

    fun release() {
        shaderStates.values.forEach { state ->
            state.node.discardDisplayList()
            state.node.setRenderEffect(null)
            state.effect = null
        }
        shaderStates.clear()
        fallback.tintFilters.clear()
        fallback.fillRestorePaint.colorFilter = null
        fallback.outlineRestorePaint.colorFilter = null
        frame = 0
    }
}

private class AndroidRangeFallbackState {
    val fillRestorePaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    val outlineRestorePaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    val layerBounds = RectF()
    val ovalBounds = RectF()
    val fillPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = NativePaint.Style.FILL
    }
    val strokePaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = NativePaint.Style.STROKE
        strokeWidth = 3f
    }
    val subtractPaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = NativePaint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    val tintFilters = HashMap<Int, PorterDuffColorFilter>()

    fun setTints(color: Color) {
        val argb = color.copy(alpha = 1f).toArgb()
        val filter = tintFilters.getOrPut(argb) { PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN) }
        fillRestorePaint.colorFilter = filter
        fillRestorePaint.alpha = (color.alpha * 0.025f * 255f).roundToInt().coerceIn(0, 255)
        outlineRestorePaint.colorFilter = filter
        outlineRestorePaint.alpha = (color.alpha * 0.52f * 255f).roundToInt().coerceIn(0, 255)
    }
}

// A separate RuntimeShader/RenderNode per group is required because a hardware Canvas may
// defer rasterization after drawRenderNode records the node reference. Reusing one node for
// several groups in a frame would make every recorded draw observe the last group's display list.
private val frameState = object : ThreadLocal<AndroidRangeFrameState>() {
    override fun initialValue() = AndroidRangeFrameState()
}

internal actual fun DrawScope.beginAeroRangeFrame(phase: Float) {
    frameState.get().beginFrame()
}

internal actual fun releaseAeroRangeResources() {
    frameState.get().release()
}

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
    val canvas = drawContext.canvas.nativeCanvas
    if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
        drawRuntimeShaderMask(canvas, group, camera, worldToScreen, worldPerPixel, phase, detailed, bounds)
    } else {
        // Older Android and software Canvas have no runtime mask shader. Paint the shared opaque
        // fill once; for the low-cost exterior edge, remove all circle interiors from one combined
        // stroke mask. Filled ranges cover shared chords, so this does not expose internal seams.
        drawNativeMaskFallback(canvas, group, camera, worldToScreen, bounds)
    }
}

private fun drawRuntimeShaderMask(
    canvas: Canvas,
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    worldPerPixel: Offset,
    phase: Float,
    detailed: Boolean,
    bounds: Rect,
) {
    val owner = frameState.get()
    val key = AndroidRangeGroupKey(group.team, group.kind)
    val state = owner.shaderStates.getOrPut(key) { AndroidRangeShaderState(key) }
    state.lastUsedFrame = owner.frame
    state.shader.setFloatUniform(
        "uWorldOrigin", camera.x + bounds.left * worldPerPixel.x,
        camera.y + bounds.top * worldPerPixel.y
    )
    state.shader.setFloatUniform("uWorldPerPixel", worldPerPixel.x, worldPerPixel.y)
    state.shader.setFloatUniform("uTint", group.color.red, group.color.green, group.color.blue, group.color.alpha)
    state.shader.setFloatUniform("uPhase", phase)
    state.shader.setFloatUniform("uDetailed", if (detailed) 1f else 0f)
    // RenderEffect is immutable with respect to shader uniforms on some Android releases.
    // Make a fresh effect after updating the cached shader uniforms, without recompiling it.
    val effect = RenderEffect.createRuntimeShaderEffect(state.shader, "content")
    state.node.setRenderEffect(effect)
    state.effect = effect

    val left = floor(bounds.left).toInt()
    val top = floor(bounds.top).toInt()
    val right = ceil(bounds.right).toInt()
    val bottom = ceil(bounds.bottom).toInt()
    state.node.setPosition(left, top, right, bottom)
    val recordingCanvas = state.node.beginRecording((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    try {
        group.circles.forEach { circle ->
            val x = (circle.center.x - camera.x) * worldToScreen.x - left
            val y = (circle.center.y - camera.y) * worldToScreen.y - top
            val rx = circle.radius * worldToScreen.x
            val ry = circle.radius * worldToScreen.y
            state.ovalBounds.set(x - rx, y - ry, x + rx, y + ry)
            recordingCanvas.drawOval(state.ovalBounds, state.maskPaint)
        }
    } finally {
        state.node.endRecording()
    }
    canvas.drawRenderNode(state.node)
}

private fun drawNativeMaskFallback(
    canvas: Canvas,
    group: AeroRangeGroup,
    camera: Offset,
    worldToScreen: Offset,
    bounds: Rect,
) {
    val state = frameState.get().fallback
    state.setTints(group.color)
    state.layerBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom)

    var checkpoint = canvas.saveLayer(state.layerBounds, state.fillRestorePaint)
    try {
        group.circles.forEach { circle ->
            val x = (circle.center.x - camera.x) * worldToScreen.x
            val y = (circle.center.y - camera.y) * worldToScreen.y
            val rx = circle.radius * worldToScreen.x
            val ry = circle.radius * worldToScreen.y
            state.ovalBounds.set(x - rx, y - ry, x + rx, y + ry)
            canvas.drawOval(state.ovalBounds, state.fillPaint)
        }
    } finally {
        canvas.restoreToCount(checkpoint)
    }

    checkpoint = canvas.saveLayer(state.layerBounds, state.outlineRestorePaint)
    try {
        group.circles.forEach { circle ->
            val x = (circle.center.x - camera.x) * worldToScreen.x
            val y = (circle.center.y - camera.y) * worldToScreen.y
            val rx = circle.radius * worldToScreen.x
            val ry = circle.radius * worldToScreen.y
            state.ovalBounds.set(x - rx, y - ry, x + rx, y + ry)
            canvas.drawOval(state.ovalBounds, state.strokePaint)
        }
        group.circles.forEach { circle ->
            val x = (circle.center.x - camera.x) * worldToScreen.x
            val y = (circle.center.y - camera.y) * worldToScreen.y
            val rx = circle.radius * worldToScreen.x
            val ry = circle.radius * worldToScreen.y
            state.ovalBounds.set(x - rx, y - ry, x + rx, y + ry)
            canvas.drawOval(state.ovalBounds, state.subtractPaint)
        }
    } finally {
        canvas.restoreToCount(checkpoint)
    }
}
