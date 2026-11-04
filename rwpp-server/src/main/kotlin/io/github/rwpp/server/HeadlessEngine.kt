/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.server

import android.content.ServerContext
import android.os.Looper
import com.corrodinggames.rts.game.RWGameEngine
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.MusicController
import com.corrodinggames.rts.gameFramework.MusicControllerNullMusicFactory
import com.corrodinggames.rts.gameFramework.network.MasterServerConnector
import com.corrodinggames.rts.gameFramework.render.GraphicsEngineNull
import com.corrodinggames.rts.gameFramework.sound.NullSoundFactory
import com.corrodinggames.rts.gameFramework.sound.SoundEngine
import com.corrodinggames.rts.java.DesktopHttpClientFactory
import java.nio.file.Files
import java.nio.file.Path

/** Platform initialization shared by vanilla hosting and custom server extensions. */
object HeadlessEngine {
    @Synchronized
    fun create(noMods: Boolean = false): RWGameEngine {
        check(GameEngine.getInstance() == null) { "The native game engine has already been created" }
        require(Files.isRegularFile(Path.of("assets", "translations", "Strings.properties"))) {
            "Game resources are missing: run rwpp-server from a directory containing assets/translations/Strings.properties"
        }
        MasterServerConnector.httpClientFactory = DesktopHttpClientFactory()
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        GameEngine.isDedicatedServerPlatform = true
        GameEngine.isDesktopPlatform = false
        GameEngine.isDesktopCanvas = false
        GameEngine.disableAutomaticGameThread = true
        GameEngine.noMods = noMods
        GameEngine.noBackground = true
        GameEngine.crashHandlerEnabled = false
        GameEngine.replayRecordingEnabledDesktop = false
        GameEngine.replayRecordingEnabledMobile = false
        GameEngine.automatedTestingCrashOnMapError = true
        GameEngine.graphicsCanvasClass = GraphicsEngineNull::class.java
        GameEngine.preCreatedGraphics = null
        SoundEngine.factory = NullSoundFactory()
        MusicController.musicFactory = MusicControllerNullMusicFactory()
        val engine = GameEngine.getOrCreate(ServerContext(), null) as? RWGameEngine
            ?: error("The native game engine did not initialize as RWGameEngine")
        check(engine.initialized) { "The native game engine is not initialized" }
        engine.settings.sendReports = false
        engine.setActivityAndGameView(null, HeadlessGameView, true)
        return engine
    }
}
