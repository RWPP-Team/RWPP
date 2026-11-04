/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.graphics

import com.corrodinggames.rts.game.TeamColorMode
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import com.corrodinggames.rts.gameFramework.render.BitmapOrTextureLazyColoring
import io.github.rwpp.graphics.SkiaNativeTextures
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

@AsmMixin("com/corrodinggames/rts/gameFramework/render/BitmapOrTextureLazyColoring")
object NativeTextureSourceMixin {
    @AsmInject(method = "colorImage(Z)V", target = InjectionPoint.HEAD, require = 1)
    fun retainSource(callback: CallbackInfo, self: Any, verbose: Boolean) {
        SkiaNativeTextures.retainColorSource(self as BitmapOrTextureLazyColoring)
    }
}

/** Retain provenance immediately after native setTeamImages publishes its eager variants. */
@AsmMixin("com/corrodinggames/rts/gameFramework/render/BitmapOrTexture")
object NativeEagerTeamColorSourceMixin {
    @AsmInject(
        method = "setTeamImages(Lcom/corrodinggames/rts/game/TeamColorMode;[Lcom/corrodinggames/rts/gameFramework/render/BitmapOrTexture;)V",
        target = InjectionPoint.RETURN,
        require = 1,
    )
    fun retainEagerSources(callback: CallbackInfo, self: Any, mode: Any) {
        // Capture only the object-typed prefix. The native array in the callback descriptor
        // would recursively load BitmapOrTexture while the host is still transforming it.
        val source = self as BitmapOrTexture
        val colorMode = mode as TeamColorMode
        source.getTeamImages(colorMode)?.let {
            SkiaNativeTextures.retainEagerTeamColorSources(source, colorMode, it)
        }
    }
}
