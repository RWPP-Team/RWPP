package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.HudComponentDataList
import io.github.rwpp.config.Settings
import io.github.rwpp.logic.hud.initializeDefaultHud
import io.github.rwpp.screen.Screen
import io.github.rwpp.screen.ScreenNavigator
import io.github.rwpp.sound.Sounds
import org.koin.dsl.koinApplication
import kotlin.test.*

class ClientStartupDefaultsTest {
    @Test
    fun emptyLegacyHudGetsUsableAeroControlsOnce() {
        val settings = Settings(hideOriginalHud = false)
        val layout = HudComponentDataList()
        initializeDefaultHud(layout)
        assertFalse(settings.hideOriginalHud, "A stored native-HUD choice must survive even an empty legacy layout")
        assertEquals(
            setOf(
                "hud_console_vertical",
                "telemetry",
                "chat",
                "events",
                "quick_selection",
                "unit_groups",
                "observer_statistics"
            ), layout.hudComponentDataList.map { it.id }.toSet()
        )
        settings.hideOriginalHud = false
        layout.hudComponentDataList.clear()
        initializeDefaultHud(layout)
        assertFalse(settings.hideOriginalHud, "A later explicit native HUD choice must survive")
        assertTrue(layout.hudComponentDataList.isEmpty())
    }

    @Test
    fun customHudLayoutIsPreserved() {
        val component = HudComponentData("telemetry", 42f, 18f)
        val layout = HudComponentDataList(mutableListOf(component))
        val settings = Settings(hideOriginalHud = false)
        initializeDefaultHud(layout)
        assertEquals(listOf(component), layout.hudComponentDataList)
        assertFalse(settings.hideOriginalHud)
    }

    @Test
    fun previousConsoleGetsIndependentSelectionPanelsOnlyOnce() {
        val console = HudComponentData("hud_console_vertical", 0f, 0f)
        val layout = HudComponentDataList(mutableListOf(console), defaultsInitialized = true)
        val settings = Settings()
        initializeDefaultHud(layout)
        assertEquals(
            listOf("hud_console_vertical", "quick_selection", "unit_groups", "observer_statistics"),
            layout.hudComponentDataList.map { it.id })
        val quick = layout.hudComponentDataList.first { it.id == "quick_selection" }
        quick.relativeX = 0.7f
        initializeDefaultHud(layout)
        assertEquals(4, layout.hudComponentDataList.size)
        assertEquals(0.7f, quick.relativeX)
        assertSame(console, layout.hudComponentDataList.first())
        layout.hudComponentDataList.removeAll { it.id == "observer_statistics" }
        initializeDefaultHud(layout)
        assertTrue(
            layout.hudComponentDataList.none { it.id == "observer_statistics" },
            "Removing the statistics panel in the editor must persist"
        )
    }

    private class TestScreen : Screen {
        @Composable
        override fun Content() = Unit
    }

    @Test
    fun gameOnlyWindowReturnsToANonemptyMenuStack() {
        val app = koinApplication {}
        try {
            val menu = TestScreen()
            val navigator = ScreenNavigator(
                TestScreen(),
                app.koin.createScope("navigation-test", org.koin.core.qualifier.named("navigation-test"))
            )
            navigator.removeFrom(0, menu)
            assertSame(menu, navigator.current)
            assertEquals(1, navigator.backStack.size)
            assertFalse(navigator.pop())
            navigator.push(TestScreen())
            navigator.removeFrom(1, TestScreen())
            assertSame(menu, navigator.current)
        } finally {
            app.close()
        }
    }

    @Test
    fun buttonsHaveStableSoundHandlesBeforeEngineEvents() {
        assertNotNull(Sounds.select)
        assertSame(Sounds.select, Sounds.select)
        assertNotSame(Sounds.hover, Sounds.select)
        assertNotNull(Sounds.closePopup)
    }
}
