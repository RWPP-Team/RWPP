/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.audio

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.MusicController
import com.corrodinggames.rts.gameFramework.MusicControllerMusicFactoryMusicSteam

internal data class MusicEntry(val path: String, val title: String)

/** Lists tracks accepted by the native loader, plus enabled mod tracks. */
internal object NativeMusicLibrary {
    fun tracks(engine: GameEngine): List<MusicEntry> {
        val music = engine.musicController ?: return emptyList()
        val paths = linkedSetOf<String>()
        val mood = Class.forName("com.corrodinggames.rts.gameFramework.MusicControllerMusicMood")
        val tracks = mood.getDeclaredField("tracks").apply { isAccessible = true }
        val folder = mood.getDeclaredMethod("getMusicFolder").apply { isAccessible = true }
        for (entry in mood.enumConstants.orEmpty()) {
            val directory = folder.invoke(entry) as String
            val accepted = tracks.get(entry) as? Array<*> ?: continue
            for (name in accepted) if (name is String) paths.add("$directory/$name")
        }
        for (path in engine.modManager.getAllMusicTracks()) if (path is String) paths.add(path)
        return paths.map { MusicEntry(it, music.getTrackDisplayName(it)) }
            .sortedWith(compareBy<MusicEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.path })
    }

    fun request(controller: MusicController, path: String) {
        MusicPlayback.exactRequest = path
        controller.requestTrack(path)
    }
}

internal object MusicPlayback {
    @Volatile
    var exactRequest: String? = null
    val requestOnThread = ThreadLocal<String?>()
}

internal expect fun disableNativeMusicLoop(track: MusicControllerMusicFactoryMusicSteam): Boolean
