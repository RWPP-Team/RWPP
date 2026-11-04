/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.game.audio

import android.media.AudioAttributes
import android.media.SoundPool
import com.corrodinggames.rts.gameFramework.GameEngine
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private object AndroidUiAudio {
    private class Pending {
        val ready = CountDownLatch(1)
        @Volatile
        var status = -1
    }

    private val pending = mutableMapOf<Int, Pending>()
    private val pool by lazy {
        SoundPool.Builder().setMaxStreams(12).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build().apply {
            setOnLoadCompleteListener { _, id, status ->
                synchronized(pending) { pending[id]?.also { it.status = status; it.ready.countDown() } }
            }
        }
    }

    fun decode(bytes: ByteArray, name: String): GameSound {
        val context = checkNotNull(GameEngine.getInstance().context)
        val directory = File(context.codeCacheDir, "rwpp-ui-audio").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$hash.${name.substringAfterLast('.', "ogg")}")
        if (!file.isFile) file.writeBytes(bytes)
        val waiting = Pending()
        val soundPool = pool
        val id = synchronized(pending) {
            soundPool.load(file.absolutePath, 1).also {
                check(it != 0) { "Android could not load interface sound $name" }
                pending[it] = waiting
            }
        }
        try {
            check(waiting.ready.await(5, TimeUnit.SECONDS) && waiting.status == 0) {
                "Android interface sound $name did not decode (status=${waiting.status})"
            }
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
        return GameSound { volume ->
            val gain = volume.coerceIn(0f, 1f)
            check(soundPool.play(id, gain, gain, 1, 0, 1f) != 0) { "Android interface sound playback failed: $name" }
        }
    }
}

internal actual fun decodePlatformSound(bytes: ByteArray, name: String): GameSound = AndroidUiAudio.decode(bytes, name)
