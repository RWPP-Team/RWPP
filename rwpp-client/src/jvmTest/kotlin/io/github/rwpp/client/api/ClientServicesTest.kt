package io.github.rwpp.client.api

import io.github.rwpp.api.game.audio.GameSoundPool
import io.github.rwpp.api.game.audio.GameSoundPoolImpl
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

class ClientServicesTest {
    @Test
    fun soundPoolIsRegisteredWithoutOpeningAnAudioDevice() {
        val application = koinApplication { modules(clientServices) }
        try {
            val pool = application.koin.get<GameSoundPool>()
            assertIs<GameSoundPoolImpl>(pool)
            assertSame(pool, application.koin.get<GameSoundPool>())
        } finally {
            application.close()
        }
    }
}
