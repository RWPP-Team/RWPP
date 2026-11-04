/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.game

import kim.der.asm.api.annotation.*

/** Shared Team state bridge used by both the Android and desktop clients. */
@AsmMixin("com/corrodinggames/rts/game/Team")
@AddInterface("io/github/rwpp/game/PlayerState")
abstract class PlayerAdapterMixin {
    @JvmField
    @field:AddField("_connectHexId")
    var connectHexIdStorage: String? = null

    @Copy("getSelf()Lcom/corrodinggames/rts/game/Team;")
    fun getSelf(): Any = this

    @Accessor("_connectHexId")
    abstract fun get_connectHexId(): String?

    @Accessor("_connectHexId")
    abstract fun set_connectHexId(value: String?)
}
