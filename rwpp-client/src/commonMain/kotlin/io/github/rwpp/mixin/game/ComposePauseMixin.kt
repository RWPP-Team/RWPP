/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.game

import io.github.rwpp.api.State
import io.github.rwpp.api.game.Game
import io.github.rwpp.appKoin
import io.github.rwpp.logic.hud.GameUiPauseState
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyReturnValue

/** Single-player simulation must also pause when the native pause menu is replaced by Compose. */
@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
object ComposePauseMixin {
    @ModifyReturnValue(method = "isGamePaused(Z)Z", require = 1)
    fun pauseBehindCompose(result: Boolean, checkingNetwork: Boolean): Boolean {
        if (!GameUiPauseState.blocked) return result
        val game = appKoin.get<Game>()
        return result || (!game.state.multiplayer && game.state.state != State.Menu)
    }
}
