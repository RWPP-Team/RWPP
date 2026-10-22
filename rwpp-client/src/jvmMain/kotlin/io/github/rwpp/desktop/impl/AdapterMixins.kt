/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.Unit
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.network.Connection
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddField
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Copy

@AsmMixin("com/corrodinggames/rts/game/Team")
@AddInterface("io/github/rwpp/game/PlayerState")
abstract class PlayerAdapterMixin {
    @JvmField
    @field:AddField("_connectHexId")
    var connectHexIdStorage: String? = null

    @JvmField
    @field:AddField("_ready")
    var readyStorage: Boolean = true

    @Copy("getSelf()Lcom/corrodinggames/rts/game/Team;")
    fun getSelf(): Any = this

    @Accessor("_connectHexId")
    abstract fun get_connectHexId(): String?

    @Accessor("_connectHexId")
    abstract fun set_connectHexId(value: String?)

    @Accessor("_ready")
    abstract fun get_ready(): Boolean

    @Accessor("_ready")
    abstract fun set_ready(value: Boolean)
}
