/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import com.corrodinggames.rts.gameFramework.render.PaintGLShader
import io.github.rwpp.graphics.DesktopWorldPostShaderEnhancements
import io.github.rwpp.graphics.WorldPostShaderSources
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/gameFramework/GameEngine")
object DesktopWorldPostProcessingMixin {
    @AsmInject(method = "canUseShaderEffects()Z", target = InjectionPoint.HEAD, cancellable = true, require = 1)
    fun enableSupportedPostProcessing(callback: CallbackInfo) {
        if (DesktopWorldPostShaderEnhancements.supportsWorldPostProcessing()) callback.setReturnValue(true)
    }
}

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object DesktopWorldPostImageMixin {
    @AsmInject(method = "setupPostprocessing()Z", target = InjectionPoint.RETURN, require = 1)
    fun installWorldPostProcessing(callback: CallbackInfo, self: Any) {
        DesktopWorldPostShaderEnhancements.updateNativePostShader(self)
    }
}

@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
object WorldPostSamplerLogMixin {
    @Redirect(
        method = "updateShaderUniforms(Lcom/corrodinggames/rts/gameFramework/render/PaintGLShader;)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/render/PaintGLShader.logShader(Ljava/lang/String;)V"
        ), require = 1
    )
    fun samplerLog(shader: PaintGLShader, message: String) {
        // Filter at the native uploader call site: shader subclasses and early-loaded native
        // shader instances also pass through it. Keep errors and missing-uniform diagnostics.
        if (!message.startsWith("Updating texture to:")) shader.logShader(message)
    }
}

/** Native uploader assigns every secondary sampler to unit 1. The displacement pass now has
 * both screenBase and material coverage: give each its own unit after the native upload.
 * Scope this to our own program; vanilla/team-colour shaders retain their native bindings.
 */
@AsmMixin("com/corrodinggames/rts/java/JavaCanvas")
object WorldDisplacementSamplerMixin {
    @AsmInject(
        method = "updateShaderUniforms(Lcom/corrodinggames/rts/gameFramework/render/PaintGLShader;)V",
        target = InjectionPoint.RETURN,
        require = 1
    )
    fun bindSamplers(callback: CallbackInfo, self: Any, shader: PaintGLShader) {
        if (shader.fragmentSource !== WorldPostShaderSources.DISPLACEMENT_FRAGMENT_SHADER || shader.errorState != 0 || shader.programId == 0) return
        var unit = 1
        try {
            for (value in shader.values) {
                val texture = value.texture ?: continue
                if (value.textureSizeUniform) continue
                val loaded: Any = texture.loadedImage
                val image = (loaded as? com.corrodinggames.rts.java.JavaBitmap)?.slickImage ?: continue
                if (value.uniformLocation < 0) continue
                org.lwjgl.opengl.GL20.glUniform1i(value.uniformLocation, unit)
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit)
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, image.texture.textureID)
                unit++
            }
        } finally {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0)
        }
    }
}
