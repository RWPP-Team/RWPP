/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.mixin.game.stats

import com.corrodinggames.rts.gameFramework.StatsHandlerTeamStats
import com.corrodinggames.rts.gameFramework.network.InputNetStream
import io.github.rwpp.api.event.hooks.StatsHistoryLoadCallbacks
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.WrapMethod

internal interface NativeStatsHistoryAccess {
    fun rwppTeamStats(): Array<StatsHandlerTeamStats?>
}

@AsmMixin("com/corrodinggames/rts/gameFramework/StatsHandler")
object StatsHistoryLoadMixin {
    @WrapMethod(method = "update()V", require = 1)
    fun guardHistorySnapshot(operation: Operation<Unit>) =
        StatsHistoryLoadCallbacks.guardHistorySnapshot { operation.call() }

    @WrapMethod(method = "read(Lcom/corrodinggames/rts/gameFramework/network/InputNetStream;Z)V", require = 1)
    fun repairStatsAfterRead(inputStream: InputNetStream, keepTeams: Boolean, operation: Operation<Unit>) =
        StatsHistoryLoadCallbacks.repairStatsAfterRead(inputStream, keepTeams) { stream, teams ->
            operation.call(stream, teams)
        }
}

@AsmMixin("com/corrodinggames/rts/gameFramework/StatsHandler")
@AddInterface("io/github/rwpp/api/mixin/game/stats/NativeStatsHistoryAccess")
abstract class NativeStatsHistoryAccessMixin {
    @Accessor("teamStats")
    abstract fun rwppTeamStats(): Array<StatsHandlerTeamStats?>
}
