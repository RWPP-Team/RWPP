package io.github.rwpp.ui

import io.github.rwpp.platform.BackActionDispatcher
import io.github.rwpp.platform.routeDesktopKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBackHandlerTest {
    @Test
    fun roomBackClosesTheNestedPickerBeforeTheOptions() {
        val engineType = com.corrodinggames.rts.gameFramework.GameEngine::class.java
        val singleton = engineType.getDeclaredField("al").apply { isAccessible = true }
        val previous = singleton.get(null)
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        singleton.set(null, unsafe.allocateInstance(com.corrodinggames.rts.game.RWGameEngine::class.java))
        try {
            val screen = BattleRoomScreen().apply {
                optionVisible = true
                recyclingUnitVisible = true
            }
            assertTrue(screen.dismissRoomOverlay())
            assertFalse(screen.recyclingUnitVisible)
            assertTrue(screen.optionVisible, "Closing the nested picker must keep the room options open")
            assertTrue(screen.dismissRoomOverlay())
            assertFalse(screen.optionVisible)
            assertFalse(screen.dismissRoomOverlay(), "Only the room itself is left to handle Back")
        } finally {
            singleton.set(null, previous)
        }
    }

    @Test
    fun pageBackOwnsOneEscapeAndDisposalRestoresNavigatorBack() {
        val dispatcher = BackActionDispatcher()
        val actions = mutableListOf<String>()
        val removeNavigator = dispatcher.register { actions += "navigator" }
        val removePage = dispatcher.register { actions += "page" }

        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("page"), actions, "A page must not also pop its navigator")
        removePage()
        removePage()
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("page", "navigator"), actions)
        removeNavigator()
        assertFalse(dispatcher.dispatch())
    }

    @Test
    fun changingEnabledStateDoesNotMoveAHandlerAheadOfItsChild() {
        val dispatcher = BackActionDispatcher()
        val actions = mutableListOf<String>()
        var parentEnabled = false
        var childEnabled = true
        dispatcher.register(enabled = { parentEnabled }) { actions += "parent" }
        dispatcher.register(enabled = { childEnabled }) { actions += "child" }

        assertTrue(dispatcher.dispatch())
        parentEnabled = true
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("child", "child"), actions)
        childEnabled = false
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("child", "child", "parent"), actions)
        parentEnabled = false
        assertFalse(dispatcher.dispatch())
    }

    @Test
    fun escapeReachesPageBackEvenWhenMenuOrHudEditorBlocksGameInput() {
        val dispatcher = BackActionDispatcher()
        var backs = 0
        var hotkeys = 0
        dispatcher.register { backs++ }

        assertTrue(routeDesktopKeyEvent(
            escapeDown = true,
            sceneConsumed = false,
            gameInputBlocked = true,
            dispatchBack = dispatcher::dispatch,
            broadcastToGame = { hotkeys++ },
        ))
        assertEquals(1, backs)
        assertEquals(0, hotkeys, "The same Escape must not also toggle the game pause menu")
    }

    @Test
    fun composeModalOrFocusedControlConsumesEscapeBeforePageBack() {
        var backs = 0
        var hotkeys = 0

        assertTrue(routeDesktopKeyEvent(
            escapeDown = true,
            sceneConsumed = true,
            gameInputBlocked = true,
            dispatchBack = { backs++; true },
            broadcastToGame = { hotkeys++ },
        ))
        assertEquals(0, backs)
        assertEquals(0, hotkeys)
    }

    @Test
    fun unhandledGameEscapeFallsThroughButOtherKeysNeverInvokeBack() {
        val dispatcher = BackActionDispatcher()
        var hotkeys = 0
        assertFalse(routeDesktopKeyEvent(
            escapeDown = true,
            sceneConsumed = false,
            gameInputBlocked = false,
            dispatchBack = dispatcher::dispatch,
            broadcastToGame = { hotkeys++ },
        ))
        assertEquals(1, hotkeys)

        var backs = 0
        dispatcher.register { backs++ }
        assertFalse(routeDesktopKeyEvent(
            escapeDown = false,
            sceneConsumed = false,
            gameInputBlocked = false,
            dispatchBack = dispatcher::dispatch,
            broadcastToGame = { hotkeys++ },
        ))
        assertEquals(0, backs, "KeyUp and non-Escape keys must not request Back")
        assertEquals(2, hotkeys)
    }

    @Test
    fun awtImeKeysDoNotBroadcastGameHotkeysAndEscapeStillReachesPageBack() {
        val dispatcher = BackActionDispatcher()
        var backs = 0
        var hotkeys = 0
        for (escapeDown in listOf(false, true)) {
            assertFalse(routeDesktopKeyEvent(
                escapeDown = escapeDown,
                sceneConsumed = false,
                gameInputBlocked = false,
                forwardToGame = false,
                dispatchBack = dispatcher::dispatch,
                broadcastToGame = { hotkeys++ },
            ))
        }
        assertEquals(0, hotkeys, "The AWT IME peer must not introduce a second game-hotkey source")

        dispatcher.register { backs++ }
        assertTrue(routeDesktopKeyEvent(
            escapeDown = true,
            sceneConsumed = false,
            gameInputBlocked = false,
            forwardToGame = false,
            dispatchBack = dispatcher::dispatch,
            broadcastToGame = { hotkeys++ },
        ))
        assertEquals(1, backs)
        assertEquals(0, hotkeys)
    }
}
