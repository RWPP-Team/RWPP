/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.desktop.ExternalGlState
import org.jetbrains.skia.*
import org.lwjgl.opengl.*
import java.util.concurrent.CopyOnWriteArrayList

internal actual val nativeWorldCanvas: Boolean get() = DesktopComposeRuntime.isAttached()

/** All passive world overlays draw into the current native target in one Skia pass. */
internal object DesktopWorldCanvases : AutoCloseable {
    private class Entry(
        val density: Density, val direction: LayoutDirection,
        val isActive: () -> Boolean, val draw: DrawScope.() -> Unit
    ) {
        val scope = CanvasDrawScope()
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    private var context: DirectContext? = null
    private var surface: Surface? = null
    private var target: BackendRenderTarget? = null
    private var targetId = -1
    private var targetWidth = 0
    private var targetHeight = 0
    private var targetStencil = -1
    private var targetSamples = -1

    fun register(density: Density, direction: LayoutDirection, draw: DrawScope.() -> Unit): AutoCloseable {
        return register(density, direction, { true }, draw)
    }

    fun register(
        density: Density, direction: LayoutDirection, isActive: () -> Boolean,
        draw: DrawScope.() -> Unit
    ): AutoCloseable {
        val entry = Entry(density, direction, isActive, draw)
        entries.add(entry)
        return AutoCloseable { entries.remove(entry) }
    }

    fun draw(engine: GameEngine) {
        if (entries.none { active(it) } || !engine.gameLoopActive) return
        ExternalGlState().use {
            // Native map/minimap/post-processing can leave a padded texture FBO current.
            // This pass belongs to the final window, after all native rendering has finished.
            val framebuffer = 0
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            val width = Display.getWidth()
            val height = Display.getHeight()
            if (width <= 0 || height <= 0) return
            GL11.glViewport(0, 0, width, height)
            val stencil = GL11.glGetInteger(GL11.GL_STENCIL_BITS)
            val samples = GL11.glGetInteger(GL13.GL_SAMPLES)
            val direct = context ?: DirectContext.makeGL().also { context = it }
            direct.resetGLAll()
            if (surface == null || targetId != framebuffer || targetWidth != width ||
                targetHeight != height || targetStencil != stencil || targetSamples != samples
            ) {
                surface?.close(); target?.close()
                target = BackendRenderTarget.makeGL(
                    width,
                    height,
                    samples,
                    stencil,
                    framebuffer,
                    FramebufferFormat.GR_GL_RGBA8
                )
                surface = Surface.makeFromBackendRenderTarget(
                    direct, target!!, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
                ) ?: error("Cannot wrap native world framebuffer for Skia overlays")
                targetId = framebuffer; targetWidth = width; targetHeight = height; targetStencil =
                    stencil; targetSamples = samples
            }
            GL20.glUseProgram(0)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(true, true, true, true)
            GL11.glDisable(32928) // GL_FRAMEBUFFER_SRGB
            direct.resetGLAll()
            val canvas = surface!!.canvas
            val checkpoint = canvas.save()
            try {
                canvas.clipRect(
                    Rect.makeXYWH(
                        0f, 0f,
                        engine.gameAreaWidth * width / engine.screenWidth.coerceAtLeast(1f), height.toFloat()
                    )
                )
                val compose = canvas.asComposeCanvas()
                val size = Size(width.toFloat(), height.toFloat())
                for (entry in entries) if (active(entry)) {
                    try {
                        entry.scope.draw(entry.density, entry.direction, compose, size, entry.draw)
                    } catch (failure: Throwable) {
                        entries.remove(entry)
                        warnHudFailure("world HUD draw", failure)
                    }
                }
            } finally {
                canvas.restoreToCount(checkpoint)
            }
            direct.flush()
            direct.resetGLAll()
        }
        // The following native GUI pass must not trust bindings from before the Skia pass.
        GL20.glUseProgram(0)
        com.corrodinggames.rts.java.JavaCanvas.currentShader = null
        com.corrodinggames.rts.java.JavaCanvas.lastBoundGraphics = null
        org.newdawn.slick.opengl.TextureImpl.unbind()
    }

    private fun active(entry: Entry): Boolean = try {
        entry.isActive()
    } catch (failure: Throwable) {
        entries.remove(entry)
        warnHudFailure("world HUD state", failure)
        false
    }

    override fun close() {
        surface?.close(); surface = null
        target?.close(); target = null
        context?.close(); context = null
    }
}

internal actual fun registerNativeWorldCanvas(
    density: Density, direction: LayoutDirection, isActive: () -> Boolean, draw: DrawScope.() -> Unit,
): AutoCloseable = DesktopWorldCanvases.register(density, direction, isActive, draw)

// Slick's final output pass draws these after native post-processing, before Compose controls.
internal actual fun drawNativeWorldCanvases(engine: GameEngine) = Unit
