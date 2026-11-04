package io.github.rwpp.ui

import io.github.rwpp.game.SPECTATOR_SPAWN_POINT
import io.github.rwpp.game.resolveSpectatorSpawnPointSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSpectatorSelectionTest {
    @Test
    fun spectatorToggleUsesNativeSentinelAndRestoresRememberedSpawn() {
        assertEquals(
            SPECTATOR_SPAWN_POINT,
            resolveSpectatorSpawnPointSelection(
                spectator = true,
                preferredSpawnPoint = 2,
                currentSpawnPoint = 1,
                maxPlayerCount = 4,
                occupiedSpawnPoints = setOf(0, 1, 2),
            ),
        )

        assertEquals(
            3,
            resolveSpectatorSpawnPointSelection(
                spectator = false,
                preferredSpawnPoint = 3,
                currentSpawnPoint = null,
                maxPlayerCount = 4,
                occupiedSpawnPoints = setOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun spectatorToggleFallsBackWhenRememberedSpawnHasBeenTaken() {
        assertEquals(
            1,
            resolveSpectatorSpawnPointSelection(
                spectator = false,
                preferredSpawnPoint = 0,
                currentSpawnPoint = 1,
                maxPlayerCount = 3,
                occupiedSpawnPoints = setOf(0),
            ),
        )
        assertNull(
            resolveSpectatorSpawnPointSelection(
                spectator = false,
                preferredSpawnPoint = null,
                currentSpawnPoint = null,
                maxPlayerCount = 0,
                occupiedSpawnPoints = emptySet(),
            ),
        )
    }
}
