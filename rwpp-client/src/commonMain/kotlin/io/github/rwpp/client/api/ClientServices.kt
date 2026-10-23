package io.github.rwpp.client.api

import io.github.rwpp.api.game.audio.GameSoundPool
import io.github.rwpp.api.game.audio.GameSoundPoolImpl
import org.koin.dsl.module

/** Install into ApiRuntime.configureKoin before starting the client UI. No audio device opens here. */
val clientServices = module {
    single<GameSoundPool> { GameSoundPoolImpl() }
}
