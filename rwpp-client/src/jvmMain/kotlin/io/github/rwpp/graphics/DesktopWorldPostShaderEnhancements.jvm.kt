/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import com.corrodinggames.rts.java.JavaCanvas
import java.util.*
import com.corrodinggames.rts.gameFramework.render.Paint as ShaderUniform

internal object DesktopWorldPostShaderEnhancements {
    private val originals = WeakHashMap<PaintGLShader, String>()
    private val originalUniforms = WeakHashMap<PaintGLShader, Array<ShaderUniform>>()
    private val installed = WeakHashMap<PaintGLShader, Boolean>()
    private val failed = WeakHashMap<PaintGLShader, Boolean>()
    internal val FRAGMENT_SHADER get() = WorldPostShaderSources.FRAGMENT_SHADER
    internal fun supportsCustomShader(
        enhanced: Boolean,
        graphicsClassName: String?,
        nativeShadersEnabled: Boolean
    ): Boolean =
        enhanced && nativeShadersEnabled && graphicsClassName == "com.corrodinggames.rts.java.JavaCanvas"

    private fun supported(graphics: Any?) = WorldGraphicsEnhancements.isEnhancedWorldRenderingEnabled() &&
            (graphics as? JavaCanvas)?.shadersEnabled == true

    internal fun supportsWorldPostProcessing(): Boolean =
        supported(GameEngine.getInstance()?.graphics)

    internal fun updateNativePostShader(owner: Any) {
        val engine = owner as GameEngine
        val access = owner as NativeDesktopWorldAccess
        val enabled = supported(engine.graphics)
        fun configure(image: com.corrodinggames.rts.game.InGameImage?, displacement: Boolean) {
            image ?: return
            val shader = (image as NativePostImageAccess).rwppPostShader() ?: return
            val active = if (displacement) updateDisplacementSource(shader, enabled) else updateSource(shader, enabled)
            if (!active) return
            shader.setFloatUniform("u_sunStrength", WorldGraphicsEnhancements.worldLightingStrength())
            DesktopWorldTerrainMaterials.bindSceneMaterials(shader, engine, image.image)
        }
        configure(access.rwppPostImage(), false)
        configure(access.rwppDisplacementImage(), true)
    }

    private fun invalidate(shader: PaintGLShader) {
        shader.values.forEach { it.uniformLocation = -1; it.dirty = true; it.unknownParameter = false }
    }

    private fun restoreUniforms(shader: PaintGLShader) {
        originalUniforms[shader]?.let { shader.values = it }
    }

    internal fun updateSource(shader: PaintGLShader, enabled: Boolean): Boolean =
        updateSource(shader, enabled, FRAGMENT_SHADER)

    internal fun updateDisplacementSource(shader: PaintGLShader, enabled: Boolean): Boolean =
        updateSource(shader, enabled, WorldPostShaderSources.DISPLACEMENT_FRAGMENT_SHADER)

    private fun updateSource(shader: PaintGLShader, enabled: Boolean, source: String): Boolean =
        synchronized(originals) {
            val original = originals.getOrPut(shader) { shader.fragmentSource }
            originalUniforms.getOrPut(shader) { shader.values }
            val wasInstalled = installed[shader] == true
            if (!enabled) {
                failed.remove(shader); installed.remove(shader)
                if (wasInstalled || shader.fragmentSource != original) {
                    shader.fragmentSource = original; restoreUniforms(shader); invalidate(shader)
                    shader.errorState = 0; shader.needsReload = true
                }
                return@synchronized false
            }
            if (failed.containsKey(shader)) return@synchronized false
            if (wasInstalled && shader.errorState != 0) {
                failed[shader] = true; installed.remove(shader)
                shader.fragmentSource = original; restoreUniforms(shader); invalidate(shader)
                shader.errorState = 0; shader.needsReload = true
                return@synchronized false
            }
            if (shader.fragmentSource != source) {
                shader.fragmentSource = source; invalidate(shader); shader.needsReload = true
            }
            installed[shader] = true
            true
        }
}
