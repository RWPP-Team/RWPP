package io.github.rwpp.api.game.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomUnitCapTargetTest {
    @Test
    fun localSkirmishHostUsesTheSinglePlayerSettingsCap() {
        assertEquals(
            RoomUnitCapTarget.SinglePlayerHost,
            roomUnitCapTarget(networked = true, singlePlayerServer = true, isServer = true),
        )
    }

    @Test
    fun remoteMultiplayerHostUsesNetworkCapFields() {
        assertEquals(
            RoomUnitCapTarget.MultiplayerHost,
            roomUnitCapTarget(networked = true, singlePlayerServer = false, isServer = true),
        )
    }

    @Test
    fun multiplayerClientAndOfflineMenuCannotApplyAHostedCap() {
        assertEquals(
            RoomUnitCapTarget.None,
            roomUnitCapTarget(networked = true, singlePlayerServer = false, isServer = false),
        )
        assertEquals(
            RoomUnitCapTarget.None,
            roomUnitCapTarget(networked = true, singlePlayerServer = true, isServer = false),
        )
        assertEquals(
            RoomUnitCapTarget.None,
            roomUnitCapTarget(networked = false, singlePlayerServer = false, isServer = false),
        )
    }
}
