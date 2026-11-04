/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.audio

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Shared asset cache with native desktop and Android playback backends. */
class GameSoundPoolImpl : GameSoundPool {
    private val sounds = ConcurrentHashMap<String, GameSound>()

    override fun newSound(input: InputStream, name: String): GameSound {
        sounds[name]?.let {
            input.close()
            return it
        }

        synchronized(sounds) {
            sounds[name]?.let {
                input.close()
                return it
            }

            val sound = input.use { stream ->
                val bytes = stream.readBytes()
                decodePlatformSound(bytes, name)
            }
            sounds[name] = sound
            return sound
        }
    }
}

internal expect fun decodePlatformSound(bytes: ByteArray, name: String): GameSound
