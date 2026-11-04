/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.replay

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin

@AsmMixin("com/corrodinggames/rts/game/RWGameEngine")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeReplaySeekAccess")
abstract class NativeReplaySeekAccessMixin {
    @Accessor("pendingGameStepTime")
    abstract fun rwppPendingGameStepTime(value: Float)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/ReplayEngine")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeReplayFileNameAccess")
abstract class NativeReplayFileNameAccessMixin {
    @Accessor("replayFileName")
    abstract fun rwppReplayFileName(): String?
}
