/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import io.github.rwpp.config.CoreData
import io.github.rwpp.config.RoomPreset
import io.github.rwpp.config.RoomPresetPlayer
import io.github.rwpp.config.SavedBlueprint
import io.github.rwpp.config.Settings
import io.github.rwpp.game.building.BuildingPlanningBlueprintItem
import io.github.rwpp.logic.config.EDITABLE_NATIVE_SETTINGS
import io.github.rwpp.logic.config.SettingsEditSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsEditSessionTest {
    @Test
    fun restoresEntryValuesInTheSameInjectedInstanceAndRetainsNativeTypes() {
        val settings = Settings(globalUiScale = 1.2f, backgroundImagePath = "entry.png")
        val injectedReference = settings
        val native = mutableMapOf<String, Any?>(
            "showFps" to true, "scrollSpeed" to 0.75f, "teamUnitCapSinglePlayer" to 240,
            "optionalPreference" to null,
        )
        val entryNative = native.toMap()
        val session = SettingsEditSession.capture(settings, native::get, { key, value -> native[key] = value }, native.keys)
        assertFalse(session.hasChanges())

        settings.globalUiScale = 1.5f
        settings.backgroundImagePath = null
        native["showFps"] = false
        native["scrollSpeed"] = 2f
        native["teamUnitCapSinglePlayer"] = 100
        native["optionalPreference"] = "preview"
        assertTrue(session.hasChanges())

        session.restore()
        assertSame(injectedReference, settings)
        assertEquals(1.2f, injectedReference.globalUiScale)
        assertEquals("entry.png", injectedReference.backgroundImagePath)
        assertEquals(entryNative, native)
        assertIs<Float>(native["scrollSpeed"])
        assertIs<Int>(native["teamUnitCapSinglePlayer"])
        assertFalse(session.hasChanges())
    }

    @Test
    fun mutableMapsAndNestedListsCannotMutateTheEntrySnapshot() {
        val bindings = mutableMapOf("send_chat" to "ENTER")
        val actionButtons = mutableMapOf("patrol" to 1)
        val players = mutableListOf(RoomPresetPlayer(1, 2, null, null))
        val disabledUnits = mutableListOf("builder")
        val presets = mutableListOf(preset(players, disabledUnits))
        val blueprintItems = mutableListOf(BuildingPlanningBlueprintItem("tank", 1, 12f, 24f))
        val blueprints = mutableListOf(SavedBlueprint("entry", blueprintItems))
        val settings = Settings(
            hotkeyBindings = bindings, actionConfirmButtons = actionButtons,
            roomPresets = presets, savedBlueprints = blueprints,
        )
        val session = SettingsEditSession.capture(settings, { error("No native keys requested") }, { _, _ -> }, emptySet())

        bindings["send_chat"] = "F2"
        actionButtons["patrol"] = 2
        players.clear()
        disabledUnits += "tank"
        blueprintItems.clear()
        assertTrue(session.hasChanges())
        session.restore()

        assertEquals(mapOf("send_chat" to "ENTER"), settings.hotkeyBindings)
        assertEquals(mapOf("patrol" to 1), settings.actionConfirmButtons)
        assertEquals(listOf(RoomPresetPlayer(1, 2, null, null)), settings.roomPresets.single().players)
        assertEquals(listOf("builder"), settings.roomPresets.single().recyclingDisabledUnits)
        assertEquals(listOf(BuildingPlanningBlueprintItem("tank", 1, 12f, 24f)), settings.savedBlueprints.single().items)
        assertFalse(session.hasChanges())

        // A discarded live value may be edited again; that must not corrupt subsequent restoration.
        assertIs<MutableMap<String, String>>(settings.hotkeyBindings)["send_chat"] = "F3"
        assertIs<MutableList<RoomPresetPlayer>>(settings.roomPresets.single().players).clear()
        session.restore()
        assertEquals("ENTER", settings.hotkeyBindings["send_chat"])
        assertEquals(1, settings.roomPresets.single().players.size)
        assertFalse(session.hasChanges())
    }

    @Test
    fun nativeOnlyChangesAreDetectedButRuntimeProgressIsNotRolledBack() {
        val coreData = CoreData(lastPlayTime = 10)
        val native = EDITABLE_NATIVE_SETTINGS.associateWith<String, Any?> { false }.toMutableMap()
        native["lastNetworkIP"] = "entry"
        native["numberOfWins"] = 5
        val session = SettingsEditSession.capture(Settings(), native::get, { key, value -> native[key] = value })

        coreData.lastPlayTime = 20
        native["lastNetworkIP"] = "current"
        native["numberOfWins"] = 6
        assertFalse(session.hasChanges())
        native["showFps"] = true
        assertTrue(session.hasChanges())
        session.restore()

        assertEquals(false, native["showFps"])
        assertEquals("current", native["lastNetworkIP"])
        assertEquals(6, native["numberOfWins"])
        assertEquals(20L, coreData.lastPlayTime)
        assertFalse(session.hasChanges())
    }

    @Test
    fun failedNativeReadsAreVisibleAndNeverConvertedToNull() {
        val captureFailure = assertFailsWith<IllegalStateException> {
            SettingsEditSession.capture(Settings(), { error("missing preference") }, { _, _ -> }, setOf("showFps"))
        }
        assertTrue(captureFailure.message.orEmpty().contains("showFps"))
        var readFails = false
        val session = SettingsEditSession.capture(
            Settings(), { if (readFails) error("unavailable") else true }, { _, _ -> }, setOf("showFps"),
        )
        readFails = true
        assertFailsWith<IllegalStateException> { session.hasChanges() }
    }

    @Test
    fun mutableNativeValuesAreRejectedAndRestorationAttemptsEveryCapturedPreference() {
        assertFailsWith<IllegalArgumentException> {
            SettingsEditSession.capture(Settings(), { mutableListOf(true) }, { _, _ -> }, setOf("showFps"))
        }
        val settings = Settings(globalUiScale = 1.1f)
        val attempts = mutableListOf<String>()
        val session = SettingsEditSession.capture(
            settings, { true }, { key, _ -> attempts += key; error("write failure") }, linkedSetOf("first", "second"),
        )
        settings.globalUiScale = 1.4f
        val failure = assertFailsWith<IllegalStateException> { session.restore() }
        assertEquals(1.1f, settings.globalUiScale)
        assertEquals(listOf("first", "second"), attempts)
        assertEquals(1, failure.suppressed.size)
    }

    private fun preset(players: List<RoomPresetPlayer>, disabledUnits: List<String>) = RoomPreset(
        name = "entry", mapPath = "maps/entry.tmx", mapType = "customMap", maxPlayers = 10,
        sharedControl = false, startingCredits = 1, startingUnits = 1, fog = 2, aiDifficulty = 0,
        income = 1f, noNukes = false, allowSpectators = true, lockedRoom = false, teamLock = false,
        players = players, recyclingDisabledUnits = disabledUnits,
    )
}
