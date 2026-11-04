/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.sound

import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.api.game.audio.GameSound
import io.github.rwpp.api.game.audio.GameSoundPool
import io.github.rwpp.appKoin
import io.github.rwpp.generated.Res
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Stable handles exist before composition; the serial worker lazily loads each native sound. */
object Sounds {
    val none: GameSound? = null
    val hover = asset("hover")
    val hover2 = asset("hover_2")
    val select = asset("select")
    val select2 = asset("select_2")
    val closePopup = asset("close_popup")
    val openPopup = asset("open_popup")
    val swipe = asset("swipe")
    val cancel = asset("cancel")

    private fun asset(name: String) = GameSound { volume ->
        runBlocking { loadSound(name) }.play(volume)
    }

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val playback = UiAudioQueue(audioScope) { failure ->
        io.github.rwpp.api.logging.logger().warn("Interface sound playback failed", failure)
    }

    fun playSound(sound: GameSound?) {
        if (sound == null) return
        playback.submit(throttle = sound === hover || sound === hover2) {
            val config = appKoin.get<ConfigIO>()
            val volume = interfaceSoundVolume(
                config.getGameConfig("enableSounds"),
                config.getGameConfig("masterVolume"), config.getGameConfig("interfaceVolume")
            )
            if (volume > 0.0) sound.play(volume.toFloat())
        }
    }

    suspend fun loadSound(name: String): GameSound = appKoin.get<GameSoundPool>()
        .newSound(Res.readBytes("files/sounds/$name.ogg").inputStream(), "$name.ogg")
}

/** Device startup and backend playback never run on the caller/game thread. Keep only the newest
 * pending UI sound so a busy audio device cannot build up a stale hover/click backlog. */
internal class UiAudioQueue(
    scope: CoroutineScope, private val intervalNanos: Long = 60_000_000L,
    onFailure: (Throwable) -> Unit = {}
) {
    private data class Request(val throttle: Boolean, val play: () -> Unit)

    private val pending = Channel<Request>(Channel.CONFLATED)

    init {
        scope.launch {
            var lastPlay: Long? = null
            for ((throttle, play) in pending) {
                val now = System.nanoTime()
                if (throttle && lastPlay?.let { now - it < intervalNanos } == true) continue
                lastPlay = now
                try {
                    play()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    onFailure(failure)
                }
            }
        }
    }

    fun submit(throttle: Boolean = true, play: () -> Unit) {
        pending.trySend(Request(throttle, play))
    }
}

internal fun interfaceSoundVolume(enabled: Boolean, master: Float, ui: Float): Double =
    if (!enabled || !master.isFinite() || !ui.isFinite()) 0.0
    else (master.coerceIn(0f, 1f) * ui.coerceIn(0f, 1f)).toDouble()
