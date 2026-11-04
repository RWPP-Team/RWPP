/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受  RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.GraphicsEngine
import com.corrodinggames.rts.gameFramework.render.GraphicsUtils
import io.github.rwpp.graphics.SkiaNativeTextures
import org.jetbrains.skia.*
import java.lang.reflect.Proxy
import android.graphics.Paint as NativePaint
import android.graphics.Rect as NativeRect
import android.graphics.RectF as NativeRectF

/** Implements the game's preview drawing contract on a Skia raster canvas, independent of Slick/GL. */
internal class SkiaNativeGraphics(private val canvas: Canvas, private val width: Int, private val height: Int) {
    val graphics: GraphicsEngine = Proxy.newProxyInstance(
        GraphicsEngine::class.java.classLoader,
        arrayOf(GraphicsEngine::class.java)
    ) { proxy, method, args ->
        val a = args ?: emptyArray()
        fun f(i: Int) = (a[i] as Number).toFloat()
        when (method.name) {
            "toString" -> "SkiaNativeGraphics($width,$height)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === a[0]
            "getWidth" -> width
            "getHeight" -> height
            "getUiScaleFactor" -> 1f
            "PENDING_unknownBoolean" -> false
            "save", "saveMatrix" -> {
                canvas.save(); null
            }

            "restore", "restoreMatrix" -> {
                canvas.restore(); null
            }

            "translate" -> {
                canvas.translate(f(0), f(1)); null
            }

            "scale" -> {
                canvas.scale(f(0), f(1)); null
            }

            "scaleAroundPoint" -> {
                canvas.translate(f(2), f(3)); canvas.scale(f(0), f(1)); canvas.translate(-f(2), -f(3)); null
            }

            "rotate" -> {
                canvas.rotate(f(0), f(1), f(2)); null
            }

            "clipRect" -> {
                if (a[0] != null) canvas.clipRect(rect(a[0]!!)); null
            }

            "drawColor", "setPorterDuffMode" -> {
                canvas.clear(a[0] as Int); null
            }

            "clearCanvas" -> {
                canvas.clear(0); null
            }

            "drawImage", "drawImageTopLeft", "drawImageSoftware", "drawImageRotated",
            "drawImageRotatedInRect", "PENDING_drawImageRotatedClipped" -> {
                val texture = a[0] as BitmapOrTexture
                val image = SkiaNativeTextures.image(texture)
                val paint = (a.lastOrNull { it is NativePaint } as? NativePaint)
                skiaPaint(paint, bitmap = true).use { p ->
                    when {
                        method.name == "drawImageRotatedInRect" -> {
                            val source = rect(a[1]!!)
                            canvas.save(); canvas.rotate(f(4) + 90f, f(2), f(3))
                            canvas.drawImageRect(
                                image, source, Rect.makeXYWH(
                                    f(2) - source.width / 2,
                                    f(3) - source.height / 2, source.width, source.height
                                ), p
                            )
                            canvas.restore()
                        }

                        a[1] is NativeRect -> canvas.drawImageRect(image, rect(a[1]!!), rect(a[2]!!), p)
                        method.name == "drawImageRotated" -> {
                            canvas.save(); canvas.rotate(f(3) + 90f, f(1), f(2))
                            canvas.drawImageRect(
                                image, Rect.makeXYWH(
                                    f(1) - texture.halfWidthFloat,
                                    f(2) - texture.halfHeightFloat, texture.width.toFloat(), texture.height.toFloat()
                                ), p
                            )
                            canvas.restore()
                        }

                        method.name == "PENDING_drawImageRotatedClipped" -> {
                            canvas.save(); canvas.translate(f(1), f(2)); canvas.scale(f(5), f(5)); canvas.rotate(f(4))
                            canvas.drawImage(image, 0f, 0f, p); canvas.restore()
                        }

                        else -> {
                            val centered = method.name == "drawImage"
                            canvas.drawImage(
                                image, f(1) - if (centered) texture.halfWidthFloat else 0f,
                                f(2) - if (centered) texture.halfHeightFloat else 0f, p
                            )
                        }
                    }
                }; null
            }

            "drawImageTiledSimple", "drawImageTiled" -> {
                GraphicsUtils.drawImageWrapped(
                    proxy as GraphicsEngine, a[0] as BitmapOrTexture,
                    a[1] as NativeRect, a[2] as? NativePaint,
                    if (a.size > 3) a[3] as Int else 0, if (a.size > 4) a[4] as Int else 0,
                    if (a.size > 5) a[5] as Int else 0, if (a.size > 6) a[6] as Int else 0
                )
                null
            }

            "drawImageWrapped" -> {
                GraphicsUtils.drawImageWrapped(
                    proxy as GraphicsEngine, a[0] as BitmapOrTexture,
                    a[1] as NativeRectF, a[2] as? NativePaint, f(3), f(4), a[5] as Int, a[6] as Int
                )
                null
            }

            "drawRect", "drawRectAlt" -> {
                skiaPaint(a[1] as? NativePaint).use { canvas.drawRect(rect(a[0]!!), it) }; null
            }

            "drawRectFromSize" -> {
                val r = a[0] as NativeRect
                skiaPaint(a[1] as? NativePaint).use {
                    canvas.drawRect(
                        Rect.makeXYWH(
                            r.left.toFloat(),
                            r.top.toFloat(),
                            r.right.toFloat(),
                            r.bottom.toFloat()
                        ), it
                    )
                }; null
            }

            "drawLine" -> {
                skiaPaint(a[4] as? NativePaint).use { canvas.drawLine(f(0), f(1), f(2), f(3), it) }; null
            }

            "drawCircle", "drawCircleNoGlow" -> {
                skiaPaint(a[3] as? NativePaint).use { canvas.drawCircle(f(0), f(1), f(2), it) }; null
            }

            "drawLines" -> {
                val points = a[0] as FloatArray
                skiaPaint(a[3] as? NativePaint).use { p ->
                    var i = a[1] as Int
                    val end = i + (a[2] as Int)
                    while (i + 3 < end) {
                        canvas.drawLine(points[i], points[i + 1], points[i + 2], points[i + 3], p); i += 4
                    }
                }; null
            }

            "drawText", "drawTextOutlined" -> {
                val paint = a[3] as? NativePaint
                Font(FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL), paint?.textSize ?: 12f).use { font ->
                    skiaPaint(paint).use { canvas.drawString(a[0] as String, f(1), f(2), font, it) }
                }; null
            }

            "measureText", "measureTextHeight" -> {
                val paint = a[1] as? NativePaint
                Font(FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL), paint?.textSize ?: 12f).use { font ->
                    if (method.name == "measureText") font.measureTextWidth(a[0] as String)
                        .toInt() else font.size.toInt()
                }
            }

            "flushBitmap", "PENDING_noop_q", "PENDING_noopPaint", "PENDING_noop_e", "applyPendingShaders",
            "PENDING_syncCanvasPublic", "beginSoftwareRender", "endSoftwareRender", "lockCanvas", "unlockCanvas" -> null

            else -> throw UnsupportedOperationException("Skia preview drawing: ${method.name}")
        }
    } as GraphicsEngine

    private fun rect(value: Any): Rect = when (value) {
        is NativeRect -> Rect.makeLTRB(
            value.left.toFloat(),
            value.top.toFloat(),
            value.right.toFloat(),
            value.bottom.toFloat()
        )

        is NativeRectF -> Rect.makeLTRB(value.left, value.top, value.right, value.bottom)
        else -> error("Unsupported native rectangle: $value")
    }

    private fun skiaPaint(native: NativePaint?, bitmap: Boolean = false) = Paint().apply {
        color = native?.color ?: -1
        isAntiAlias = native?.isAntiAlias ?: true
        strokeWidth = native?.strokeWidth ?: 1f
        mode = if (native?.style == NativePaint.Style.STROKE) PaintMode.STROKE else PaintMode.FILL
        if (bitmap && color != -1) {
            colorFilter = ColorFilter.makeLighting(color or 0xff000000.toInt(), 0xff000000.toInt())
            alpha = native?.alpha ?: 255
        }
        (native?.colorFilter as? android.graphics.LightingColorFilter)?.let { filter ->
            val multiply = filter.javaClass.getField("mMul").getInt(filter)
            val add = filter.javaClass.getField("mAdd").getInt(filter)
            if (multiply != 0 && multiply != -1) {
                colorFilter = ColorFilter.makeLighting(multiply, add)
                blendMode = BlendMode.PLUS
            }
        }
    }
}

