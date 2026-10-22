/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.units.comp.EntityRangeUnitComp

class OffscreenSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    @Volatile
    private var renderSession: RenderSession? = null

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    init {
        holder.addCallback(this)
        // Keep the range surface above the game SurfaceView but below the normal window layer,
        // so Compose controls can remain interactive and visible over the range circles.
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderSession?.stop()
        val session = RenderSession()
        renderSession = session
        Thread({ renderLoop(session) }, "RWPP-RangeRenderer").apply {
            isDaemon = true
            session.thread = this
            start()
        }
    }

    private fun renderLoop(session: RenderSession) {
        var renderer: RangeRenderer? = null
        try {
            while (session.running && renderSession === session) {
                val width = surfaceWidth
                val height = surfaceHeight
                if (width <= 0 || height <= 0) {
                    waitForNextFrame(session)
                    continue
                }

                if (renderer == null || renderer.width != width || renderer.height != height) {
                    renderer?.release()
                    renderer = try {
                        RangeRenderer(width, height)
                    } catch (failure: Exception) {
                        Log.w(TAG, "Could not allocate the range overlay for ${width}x$height", failure)
                        waitForNextFrame(session)
                        continue
                    }
                }
                val currentRenderer = renderer ?: continue

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockHardwareCanvas()
                    if (canvas != null && session.running && renderSession === session) {
                        currentRenderer.render(canvas)
                    }
                } catch (failure: Exception) {
                    if (session.running && renderSession === session) {
                        Log.w(TAG, "Could not render the range overlay", failure)
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (failure: Exception) {
                            if (session.running && renderSession === session) {
                                Log.w(TAG, "Could not post the range overlay frame", failure)
                            }
                        }
                    }
                }
                waitForNextFrame(session)
            }
        } finally {
            renderer?.release()
            session.running = false
            if (renderSession === session) renderSession = null
        }
    }

    private fun waitForNextFrame(session: RenderSession) {
        if (!session.running) return
        try {
            Thread.sleep(FRAME_DELAY_MS)
        } catch (_: InterruptedException) {
            // Surface destruction interrupts the render thread so it can release its bitmaps promptly.
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, he: Int) {
        surfaceWidth = w
        surfaceHeight = he
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceWidth = 0
        surfaceHeight = 0
        renderSession?.also {
            renderSession = null
            it.stop()
        }
    }

    private class RenderSession {
        @Volatile
        var running = true

        lateinit var thread: Thread

        fun stop() {
            running = false
            if (::thread.isInitialized) thread.interrupt()
        }
    }

    private companion object {
        const val TAG = "RWPP-RangeRenderer"
        const val FRAME_DELAY_MS = 16L
    }

    class RangeRenderer(val width: Int, val height: Int) {
        private val teamBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val teamCanvas = Canvas(teamBitmap)

        // 2. 总输出层：用于存放所有队伍叠加后的结果
        private val finalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val finalCanvas = Canvas(finalBitmap)

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
            // 不需要特殊的 Xfermode，因为我们要的是覆盖效果
        }

        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // SRC_IN: 只有在 teamCanvas 有圆的地方才染上队色
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }

        fun release() {
            if (!teamBitmap.isRecycled) teamBitmap.recycle()
            if (!finalBitmap.isRecycled) finalBitmap.recycle()
        }

        fun render(mainCanvas: Canvas) = with(EntityRangeUnitComp) {
            // A. 先清空“最终成品图”
            finalCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            beforeDrawRange()

            layerGroups.forEach { (team, units) ->
                // B. 处理这一支队伍
                // 1. 清空当前队伍的临时层
                teamCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // 2. 在临时层画圆
                teamCanvas.save()
                teamCanvas.scale(GameEngine.getInstance().dipScale, GameEngine.getInstance().dipScale)
                units.forEach { unit ->
                    val range = (unit as? OrderableUnit)?.getMaxAttackRange() ?: 0f
                    drawRangeWith(unit, circlePaint, range) { x, y, radius, paint ->
                        teamCanvas.drawCircle(x, y, radius, paint)
                    }
                }
                teamCanvas.restore()

                // 3. 给当前队伍上色 (此时 teamBitmap 里是这一队带透明度的圆)
                tintPaint.color = getTeamPaint(team).color
                teamCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)

                // 4. 重要：把这一队的结果“合并”到总输出层
                // 使用默认混合模式 (SRC_OVER)，这样不同队伍的圈会叠在一起
                finalCanvas.drawBitmap(teamBitmap, 0f, 0f, null)
            }

            // C. 最后：一次性把所有队伍的结果画到主屏幕
            // 先清理主屏幕，再画成品
            mainCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            mainCanvas.drawBitmap(finalBitmap, 0f, 0f, null)
        }
    }
}
