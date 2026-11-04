/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.java.JavaCanvas
import org.lwjgl.opengl.*
import org.newdawn.slick.opengl.TextureImpl

/** Isolate Slick uploads from pixel-store state left by another renderer sharing the context. */
internal fun <T> withSlickTextureUploadState(block: () -> T): T {
    val names = intArrayOf(
        GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS
    )
    val previous = names.map(GL11::glGetInteger)
    val previousBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)
    try {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        for (index in 1 until names.size) GL11.glPixelStorei(names[index], 0)
        return block()
    } finally {
        names.forEachIndexed { index, name -> GL11.glPixelStorei(name, previous[index]) }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousBuffer)
    }
}

internal actual fun withNativeModReloadGraphics(engine: GameEngine, reload: () -> Unit) {
    val canvas = engine.graphics as? JavaCanvas ?: return reload()
    check(Display.isCurrent()) { "Mod textures must be loaded on the native Slick GL thread" }
    synchronized(engine.renderLock) {
        canvas.PENDING_syncCanvasPublic()
        try {
            withSlickTextureUploadState {
                try {
                    reload()
                } finally {
                    canvas.PENDING_syncCanvasPublic()
                }
            }
        } finally {
            // Loading and recoloring create GL textures outside JavaCanvas' paint cache.
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL20.glUseProgram(0)
            JavaCanvas.currentShader = null
            JavaCanvas.lastBoundGraphics = null
            TextureImpl.unbind()
            canvas.makeCurrent()
        }
    }
}
