/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.api.game.audio

import korlibs.audio.sound.Sound
import korlibs.audio.sound.readSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** One KorAU backend for both Android and desktop, independent of the game's platform sound engine. */
class GameSoundPoolImpl : GameSoundPool {
    private val sounds = ConcurrentHashMap<String, Sound>()

    override fun newSound(input: InputStream, name: String): Sound {
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
                runBlocking(Dispatchers.IO) { bytes.readSound() }
            }
            sounds[name] = sound
            return sound
        }
    }
}
