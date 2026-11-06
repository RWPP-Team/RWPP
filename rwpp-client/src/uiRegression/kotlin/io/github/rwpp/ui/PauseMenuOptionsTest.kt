package io.github.rwpp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.rwpp.game.room.pauseMenuOptions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseMenuOptionsTest {
    @Test
    fun localGameHasBriefingButNoNetworkCommands() {
        val options = pauseMenuOptions(false, true, false, false, false, true)
        assertFalse(options.chat); assertFalse(options.players); assertFalse(options.returnRoom); assertFalse(options.surrender)
        assertTrue(options.briefing)
    }

    @Test
    fun roomReturnIsHostOnlyAndSurrenderFollowsNativeDefeatAndVictoryFlags() {
        val host = pauseMenuOptions(true, true, false, false, false, true)
        assertTrue(host.chat); assertTrue(host.players); assertTrue(host.returnRoom); assertTrue(host.surrender)
        assertFalse(host.briefing)
        val client = pauseMenuOptions(true, false, false, false, false, false)
        assertTrue(client.chat); assertTrue(client.players); assertFalse(client.returnRoom); assertTrue(client.surrender)
        assertFalse(pauseMenuOptions(true, false, true, false, false, false).surrender)
        assertFalse(pauseMenuOptions(true, true, false, true, false, false).surrender)
        assertTrue(pauseMenuOptions(false, false, false, false, true, false).hideInterface)
    }

    @Test
    fun saveAndLoadAreOfferedOnlyForSinglePlayerWithSaves() {
        assertTrue(pauseMenuOptions(false, true, false, false, false, false).saveLoad)
        assertTrue(pauseMenuOptions(false, false, false, false, false, false).saveLoad)
        assertFalse(pauseMenuOptions(false, false, false, false, true, false).saveLoad, "Replays cannot write saves")
        assertFalse(pauseMenuOptions(true, true, false, false, false, false).saveLoad, "Networked games own no saves")
    }

    @Test
    fun onlyCurrentProductionIconAndPopupContentsKeepTheQuantityPanelOpen() {
        val popup = Rect(200f, 100f, 490f, 300f)
        assertFalse(queueDismissesForPoint(Offset(600f, 180f), popup, "tank", "tank"))
        assertFalse(queueDismissesForPoint(Offset(250f, 200f), popup, "tank", null))
        assertTrue(queueDismissesForPoint(Offset(600f, 180f), popup, "tank", "builder"))
        assertTrue(
            queueDismissesForPoint(Offset(600f, 450f), popup, "tank", null),
            "Blank space inside the console must dismiss"
        )
        assertTrue(queueDismissesForPoint(Offset(50f, 550f), popup, "tank", null))
    }

    @Test
    fun canvasDoesNotInvalidateForFreshCallbacksButDoesForVisibleProgress() {
        val first = CommandFrame(title = "Factory", buttons = listOf(CommandButton("Tank", activate = {})))
        val fresh = first.copy(buttons = listOf(CommandButton("Tank", activate = {})))
        assertTrue(sameCommandVisuals(first, fresh))
        assertFalse(sameCommandVisuals(first, fresh.copy(buttons = listOf(CommandButton("Tank", progress = 0.2f)))))
        assertFalse(sameCommandVisuals(first, fresh.copy(buttons = listOf(CommandButton("Tank", queued = 1)))))
        assertFalse(sameCommandVisuals(first, fresh.copy(buttons = listOf(CommandButton("Tank", canAdd = false)))))
    }
}
