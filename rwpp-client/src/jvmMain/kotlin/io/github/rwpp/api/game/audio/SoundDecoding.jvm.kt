/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.audio

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.newdawn.slick.openal.OggDecoder
import java.io.BufferedInputStream

/** Slick decodes UI assets; the game owns the one process-wide OpenAL context. */
private object DesktopUiAudio {
    private var sources = IntArray(0)
    private var nextSource = 0

    @Synchronized
    fun decode(bytes: ByteArray, name: String): GameSound {
        check(AL.isCreated()) { "The game's OpenAL device is unavailable" }
        val data = BufferedInputStream(bytes.inputStream()).use { OggDecoder().getData(it) }
        check(data.channels == 1 || data.channels == 2) { "Unsupported interface sound channels: $name" }
        val buffer = AL10.alGenBuffers()
        check(buffer != 0) { "Could not allocate interface sound buffer: $name" }
        try {
            AL10.alBufferData(
                buffer,
                if (data.channels == 1) AL10.AL_FORMAT_MONO16 else AL10.AL_FORMAT_STEREO16,
                data.data, data.rate
            )
            check(AL10.alGetError() == AL10.AL_NO_ERROR) { "Could not decode interface sound: $name" }
        } catch (failure: Throwable) {
            AL10.alDeleteBuffers(buffer)
            throw failure
        }
        return GameSound { volume -> play(buffer, volume) }
    }

    @Synchronized
    private fun play(buffer: Int, volume: Float) {
        if (sources.isEmpty()) {
            sources = IntArray(8) {
                AL10.alGenSources().also { source ->
                    check(source > 0) { "Could not allocate interface audio source" }
                    AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
                    AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f)
                }
            }
        }
        val source = sources.firstOrNull { AL10.alGetSourcei(it, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING }
            ?: sources[nextSource++ % sources.size]
        AL10.alSourceStop(source)
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer)
        AL10.alSourcef(source, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
        AL10.alSourcef(source, AL10.AL_PITCH, 1f)
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE)
        AL10.alSourcePlay(source)
        check(AL10.alGetError() == AL10.AL_NO_ERROR) { "Interface audio playback failed" }
    }
}

internal actual fun decodePlatformSound(bytes: ByteArray, name: String): GameSound = DesktopUiAudio.decode(bytes, name)
