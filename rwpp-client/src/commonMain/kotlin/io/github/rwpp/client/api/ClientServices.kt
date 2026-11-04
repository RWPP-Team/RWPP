/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.client.api

import io.github.rwpp.api.game.audio.GameSoundPool
import io.github.rwpp.api.game.audio.GameSoundPoolImpl
import io.github.rwpp.game.map.MapPreviewGenerationService
import org.koin.dsl.module

/** Install into ApiRuntime.configureKoin before starting the client UI. No audio device opens here. */
val clientServices = module {
    single<GameSoundPool> { GameSoundPoolImpl() }
    single { MapPreviewGenerationService() }
}
