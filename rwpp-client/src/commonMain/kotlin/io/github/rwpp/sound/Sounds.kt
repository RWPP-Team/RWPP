/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.sound

import io.github.rwpp.appKoin
import io.github.rwpp.api.Initialization
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.api.event.events.GameLoadedEvent
import korlibs.audio.sound.Sound
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.audio.GameSoundPool
import io.github.rwpp.generated.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Sounds : Initialization {
    val none: Sound? = null

    var hover: Sound? = none
    var hover2: Sound? = none
    var select: Sound? = none
    var select2: Sound? = none
    var closePopup: Sound? = none
    var openPopup: Sound? = none
    var swipe: Sound? = none
    var cancel: Sound? = none

    override fun init() {
        GlobalEventChannel.filter(GameLoadedEvent::class).subscribeOnce {
            withContext(Dispatchers.IO) {
                hover = loadSound("hover")
                hover2 = loadSound("hover_2")
                select = loadSound("select")
                select2 = loadSound("select_2")
                closePopup = loadSound("close_popup")
                openPopup = loadSound("open_popup")
                swipe = loadSound("swipe")
                cancel = loadSound("cancel")
            }
        }
    }

    private var lastPlayTime = 0L
    private const val PLAY_INTERVAL_MS = 60L
    fun playSound(sound: Sound?) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPlayTime >= PLAY_INTERVAL_MS) {
            lastPlayTime = currentTime
            sound?.play(GameScope.coroutineContext)
        }
    }

    suspend fun loadSound(name: String): Sound {
        val pool = appKoin.get<GameSoundPool>()
        return pool.newSound(Res.readBytes("files/sounds/$name.ogg").inputStream(), "$name.ogg")
    }
}
