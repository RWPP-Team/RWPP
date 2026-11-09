/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.mixin.music

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.MusicController
import com.corrodinggames.rts.gameFramework.MusicControllerMusicFactoryMusicSteam
import io.github.rwpp.game.audio.MusicPlayback
import io.github.rwpp.game.audio.disableNativeMusicLoop
import io.github.rwpp.game.nativeaccess.NativeMusicAccess
import io.github.rwpp.logic.GameVisualSettings
import kim.der.asm.api.annotation.*

@AsmMixin("com/corrodinggames/rts/gameFramework/MusicController")
@AddInterface("io/github/rwpp/game/nativeaccess/NativeMusicAccess")
abstract class NativeMusicAccessMixin {
    @Accessor("currentTrack")
    abstract fun rwppTrack(): MusicControllerMusicFactoryMusicSteam?

    @Accessor("trackLoaded")
    abstract fun rwppTrackLoaded(): Boolean

    @Accessor("currentTrackNoLoop")
    abstract fun rwppNoLoop(): Boolean

    @Accessor("currentTrackNoLoop")
    abstract fun rwppNoLoop(value: Boolean)

    @Accessor("modMusicCheckPending")
    abstract fun rwppModMusicPending(value: Boolean)

    @Accessor("pendingNowPlayingMessage")
    abstract fun rwppNowPlaying(value: String?)
}

@AsmMixin("com/corrodinggames/rts/gameFramework/MusicController")
object NativeMusicManagerMixin {
    @AsmInject(method = "playMusic(F)V", target = InjectionPoint.HEAD, require = 1)
    fun before(callback: CallbackInfo, self: Any) {
        val controller = self as MusicController
        MusicPlayback.requestOnThread.set(MusicPlayback.exactRequest?.takeIf {
            controller.trackChangeRequested && controller.requestedTrackPath == it
        })
        val access = self as NativeMusicAccess
        if (GameVisualSettings.get()?.playMusicToEnd == true && access.rwppTrackLoaded()) {
            access.rwppModMusicPending(false)
            if (!access.rwppNoLoop()) {
                val track = access.rwppTrack()
                if (track != null && disableNativeMusicLoop(track)) access.rwppNoLoop(true)
            }
        }
    }

    @Redirect(
        method = "playMusic(F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/MusicController.getAllMoodTracks()Ljava/util/ArrayList;"
        ), require = 1
    )
    fun available(self: Any): ArrayList<Any?> {
        val controller = self as MusicController
        val native = controller.getAllMoodTracks() as ArrayList<Any?>
        MusicPlayback.requestOnThread.get()?.let { native.add(0, it) }
        return native
    }

    @Redirect(
        method = "playMusic(F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/MusicController.getTrackDisplayName(Ljava/lang/String;)Ljava/lang/String;"
        ), require = 1
    )
    fun title(self: Any, path: String): String {
        if (path == MusicPlayback.requestOnThread.get()) return path
        return (self as MusicController).getTrackDisplayName(path)
    }

    @Redirect(
        method = "playMusic(F)V", at = At(
            value = InjectionPoint.INVOKE,
            target = "com/corrodinggames/rts/gameFramework/MusicControllerMusicFactoryMusicSteam.playTrack(Z)V"
        ), require = 1
    )
    fun play(track: MusicControllerMusicFactoryMusicSteam, loop: Boolean) {
        if (GameVisualSettings.get()?.playMusicToEnd == true) {
            (GameEngine.getInstance().musicController as NativeMusicAccess).rwppNoLoop(true)
            track.playTrack(false)
            // A cached desktop music handle may still carry the loop flag from an earlier play.
            disableNativeMusicLoop(track)
        } else track.playTrack(loop)
    }

    @AsmInject(method = "playMusic(F)V", target = InjectionPoint.TAIL, require = 1)
    fun after(callback: CallbackInfo, self: Any) {
        val requested = MusicPlayback.requestOnThread.get() ?: return
        val controller = self as MusicController
        (self as NativeMusicAccess).rwppNowPlaying("Now playing: " + controller.getTrackDisplayName(requested))
        if (MusicPlayback.exactRequest == requested) MusicPlayback.exactRequest = null
        MusicPlayback.requestOnThread.remove()
    }
}
