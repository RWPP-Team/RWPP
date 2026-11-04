/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.graphics.*

internal actual fun prepareHudTexture(texture: BitmapOrTexture) {
    SkiaNativeTextures.image(texture)
}

internal actual fun readHudTexturePixels(
    texture: BitmapOrTexture, left: Int, top: Int,
    sourceWidth: Int, sourceHeight: Int, width: Int, height: Int
): IntArray =
    SkiaNativeTextures.pixels(texture).let { source ->
        IntArray(width * height) { i ->
            source[(top + i / width * sourceHeight / height) * texture.width + left + i % width * sourceWidth / width]
        }
    }

internal actual fun hudImageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    skiaImageFromArgb(pixels, width, height).toComposeImageBitmap()

/** Slick executes every native texture/shader; Compose receives an owned pixel copy. */
internal actual fun withNativeHudCanvas(draw: (com.corrodinggames.rts.gameFramework.render.GraphicsEngine) -> Unit): ImageBitmap {
    val engine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance()
    check(Thread.holdsLock(engine.renderLock)) { "HUD images require the native render boundary" }
    val original = engine.graphics as com.corrodinggames.rts.java.JavaCanvas
    original.PENDING_syncCanvasPublic()
    val image = original.createImage(96, 96, true)
    val canvas = original.createBitmapCanvas(image) as com.corrodinggames.rts.java.JavaCanvas
    try {
        engine.graphics = canvas
        canvas.setPorterDuffMode(0, android.graphics.PorterDuff.Mode.CLEAR)
        draw(canvas)
        canvas.flushBitmap()
        val bytes = org.lwjgl.BufferUtils.createByteBuffer(96 * 96 * 4)
        // Slick FBOs use texture coordinates (logical y=0 at GL y=0). Graphics.getArea
        // instead subtracts from the padded texture height: a 96px image in a 128px texture
        // would lose the first 32 rows and read padding. Read the actual native target region.
        canvas.makeCurrent()
        org.lwjgl.opengl.GL11.glReadPixels(
            0, 0, 96, 96,
            org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, bytes
        )
        val pixels = IntArray(96 * 96) { i ->
            val offset = i * 4
            ((bytes.get(offset + 3).toInt() and 255) shl 24) or
                    ((bytes.get(offset).toInt() and 255) shl 16) or
                    ((bytes.get(offset + 1).toInt() and 255) shl 8) or (bytes.get(offset + 2).toInt() and 255)
        }
        return hudImageFromPixels(pixels, 96, 96)
    } finally {
        engine.graphics = original
        try {
            canvas.PENDING_syncCanvasPublic(); canvas.PENDING_noop_q(); image.disposeBitmap()
        } finally {
            original.makeCurrent()
        }
    }
}
