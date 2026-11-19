package io.github.rwpp.hud

import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HudEditSessionTest {
    @Test
    fun discardRestoresMovedScaledDeletedAndImportedComponentsAndHudOptions() {
        val settings = Settings()
        val first = component("chat", 0.2f, 0.3f)
        val second = component("telemetry", 0.7f, 0.1f)
        val originalData = listOf(first.data.copy(), second.data.copy())
        val session = HudEditSession(listOf(first, second), settings)
        val current = mutableListOf(first, second)

        first.moveBy(80f, 40f, 1000f, 720f)
        first.scale = 1.5f
        current.remove(second)
        current.add(component("hud_console_vertical", 0.5f, 0.5f))
        settings.hudAnimations = false
        settings.hudChatShowTeam = true
        settings.commandTooltipScale = 1.6f
        settings.hudCustomResourcesPosition = "above"
        settings.hudCustomResourcesLayout = "horizontal"
        settings.enableAnimations = false // This setting belongs to the separate settings page.
        assertTrue(session.hasChanges(current), "Esc must ask before leaving a modified layout")

        session.discard { restored ->
            current.clear()
            current.addAll(restored.map { data ->
                listOf(first, second).first { it.data === data }
            })
        }

        assertFalse(session.isActive, "Late import callbacks must not mutate a discarded layout")
        assertEquals(originalData, current.map { it.data })
        assertSame(first, current[0])
        assertSame(second, current[1])
        assertEquals(originalData[0].xDp, first.x)
        assertEquals(originalData[0].yDp, first.y)
        assertEquals(1f, first.scale)
        assertTrue(settings.hudAnimations)
        assertFalse(settings.hudChatShowTeam)
        assertEquals(1f, settings.commandTooltipScale)
        assertEquals("below", settings.hudCustomResourcesPosition)
        assertEquals("vertical", settings.hudCustomResourcesLayout)
        assertFalse(settings.enableAnimations, "Discard only rolls back settings owned by the HUD editor")
    }

    @Test
    fun unchangedEscapeDoesNotRequireSavingAfterViewportMeasurementOrResize() {
        val state = HudComponentState(HudComponentData("chat", 100f, 40f))
        val session = HudEditSession(listOf(state), Settings())
        state.width = 200f
        state.height = 100f
        state.updateViewport(1000f, 720f)
        assertFalse(session.hasChanges(listOf(state)), "Legacy anchor migration is not a user edit")

        state.updateViewport(1400f, 900f)
        assertFalse(session.hasChanges(listOf(state)), "A window resize alone must not open the save prompt")
        var replaced = false
        session.discard { replaced = true }
        assertTrue(replaced)
    }

    @Test
    fun continueEditingKeepsDraftAndSavingCommitsOnceWithoutLaterRollback() {
        val settings = Settings()
        val state = component("chat", 0.2f, 0.3f)
        val session = HudEditSession(listOf(state), settings)
        state.moveBy(50f, 20f, 1000f, 720f)
        settings.hudChatShowTimestamp = true
        assertTrue(session.hasChanges(listOf(state)))
        assertTrue(session.hasChanges(listOf(state)), "Continuing after Esc must preserve the draft")
        var writes = 0
        session.save(listOf(state)) {
            writes++
            assertEquals(state.x, state.data.xDp)
            assertEquals(state.y, state.data.yDp)
        }.getOrThrow()
        session.save(listOf(state)) { error("Repeated save clicks must not write the layout twice") }.getOrThrow()
        session.discard { error("A saved edit must not be rolled back when its composition leaves") }
        assertEquals(1, writes)
        assertTrue(settings.hudChatShowTimestamp)
    }

    @Test
    fun failedSaveLeavesDraftAvailableToContinueOrDiscard() {
        val settings = Settings()
        val state = component("chat", 0.2f, 0.3f)
        val original = state.data.copy()
        val session = HudEditSession(listOf(state), settings)
        state.moveBy(50f, 0f, 1000f, 720f)
        settings.hudChatShowTimestamp = true
        assertTrue(session.save(listOf(state)) { error("disk full") }.isFailure)
        assertTrue(session.isActive)
        assertTrue(session.hasChanges(listOf(state)))
        session.discard { }
        assertEquals(original, state.data)
        assertFalse(settings.hudChatShowTimestamp)
    }

    private fun component(id: String, x: Float, y: Float) = HudComponentState(
        HudComponentData(id, 0f, 0f, relativeX = x, relativeY = y)
    ).apply {
        width = 200f
        height = 100f
        updateViewport(1000f, 720f)
        syncToData()
    }
}
