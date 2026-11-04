package io.github.rwpp.ui

import io.github.rwpp.config.HudComponentData
import io.github.rwpp.game.hud.unitHealthRatio
import io.github.rwpp.hud.HudComponentState
import io.github.rwpp.logic.hotkeys.validHotkeyBindings
import kotlin.test.*

class AeroUiTest {
    @Test
    fun hotkeyValidationAcceptsModifiersAndTwoAlternatives() {
        val keys = setOf("T", "SPACE", "A", "ENTER")
        listOf("", "T", "SPACE", "CTRL+A", "SHIFT+ENTER,CTRL+T").forEach {
            assertTrue(validHotkeyBindings(it, keys), it)
        }
        listOf("CTRL+", "CTRL+CTRL+A", "META+A", "T,Y,U", "not_a_key").forEach {
            assertFalse(
                validHotkeyBindings(it, keys),
                it
            )
        }
    }

    @Test
    fun interfaceSoundsHonorMuteAndBothVolumeControls() {
        assertEquals(0.0, io.github.rwpp.sound.interfaceSoundVolume(false, 1f, 1f))
        assertEquals(0.25, io.github.rwpp.sound.interfaceSoundVolume(true, 0.5f, 0.5f))
        assertEquals(0.0, io.github.rwpp.sound.interfaceSoundVolume(true, Float.NaN, 1f))
        assertEquals(1.0, io.github.rwpp.sound.interfaceSoundVolume(true, 2f, 1f))
    }

    @Test
    fun sameNamedMapsInDifferentDirectoriesCannotSharePreviewCache() {
        fun map(path: String) = io.github.rwpp.api.game.map.GameMap(
            0, "same",
            com.corrodinggames.rts.gameFramework.network.GameSetupMapType.skirmishMap,
            { byteArrayOf().inputStream() }, enginePath = path
        )
        assertNotEquals(
            io.github.rwpp.coil.mapPreviewKey(map("maps/a/same.tmx")),
            io.github.rwpp.coil.mapPreviewKey(map("maps/b/same.tmx"))
        )
    }

    @Test
    fun damagedAndShieldlessUnitsHaveFiniteRatios() {
        assertEquals(0.25f, unitHealthRatio(25f, 100f))
        assertEquals(0f, unitHealthRatio(0f, 0f))
        assertEquals(0f, unitHealthRatio(-1f, 100f))
        assertEquals(1f, unitHealthRatio(200f, 100f))
    }

    @Test
    fun saveNamesCannotEscapeSaveDirectory() {
        assertTrue(validSaveName("测试存档_01"))
        listOf("", " ", "../save", "a/b", "a\\b", "a:b", "a\n", " a", "a.").forEach {
            assertFalse(validSaveName(it), it)
        }
    }

    @Test
    fun duplicateHudTemplatesHaveIndependentIdentityAndPersistEdits() {
        val first = HudComponentData("hud_console", 10f, 20f)
        val second = HudComponentData("hud_console", 10f, 20f)
        assertNotEquals(first.instanceId, second.instanceId)
        val state = HudComponentState(first)
        state.x = 42f
        state.y = 64f
        state.scale = 0.75f
        state.syncToData()
        assertEquals(42f, first.xDp)
        assertEquals(64f, first.yDp)
        assertEquals(0.75f, first.scale)
        assertEquals(10f, second.xDp)
    }
}
