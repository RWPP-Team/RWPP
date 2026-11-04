/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture

internal actual fun prepareHudTexture(texture: BitmapOrTexture) {
    texture.ensurePixelData()
}

internal actual fun readHudTexturePixels(
    texture: BitmapOrTexture, left: Int, top: Int,
    sourceWidth: Int, sourceHeight: Int, width: Int, height: Int
): IntArray {
    prepareHudTexture(texture)
    return IntArray(width * height) { i ->
        texture.getPixel(
            left + i % width * sourceWidth / width,
            top + i / width * sourceHeight / height
        )
    }
}

internal actual fun hudImageFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal actual fun withNativeHudCanvas(draw: (com.corrodinggames.rts.gameFramework.render.GraphicsEngine) -> Unit): ImageBitmap {
    val engine = com.corrodinggames.rts.gameFramework.GameEngine.getInstance()
    check(Thread.holdsLock(engine.renderLock)) { "HUD images require the native render boundary" }
    val original = engine.graphics
    val image = original.createImage(96, 96, true)
    image.bitmap.density = Bitmap.DENSITY_NONE
    val canvas = original.createBitmapCanvas(image)
    try {
        engine.graphics = canvas
        canvas.setPorterDuffMode(0, android.graphics.PorterDuff.Mode.CLEAR)
        draw(canvas)
        canvas.flushBitmap()
        // Texture.pixelData may be an older snapshot; copy the completed native Bitmap itself.
        val pixels = IntArray(96 * 96)
        image.bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        return hudImageFromPixels(pixels, 96, 96)
    } finally {
        engine.graphics = original
        try {
            canvas.PENDING_noop_q(); image.disposeBitmap()
        } finally {
            original.PENDING_syncCanvasPublic()
        }
    }
}
