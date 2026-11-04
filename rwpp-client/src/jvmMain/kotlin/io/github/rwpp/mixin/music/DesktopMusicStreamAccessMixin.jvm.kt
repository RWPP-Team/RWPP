/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.music

import com.corrodinggames.rts.java.OpenALSoundEngine
import com.corrodinggames.rts.java.audio.Music
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.AsmMixin

@AsmMixin("com/corrodinggames/rts/java/OpenALMusicPlayer")
@AddInterface("io/github/rwpp/game/nativeaccess/DesktopMusicStreamAccess")
abstract class DesktopMusicStreamAccessMixin {
    @Accessor("soundEngine")
    abstract fun rwppSoundEngine(): OpenALSoundEngine
    @Accessor("playingMusic")
    abstract fun rwppMusic(): Music?
    @Accessor("PENDING_booleanE")
    abstract fun rwppLoopRequested(value: Boolean)
}
