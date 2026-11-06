package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.InputControllerButton
import com.corrodinggames.rts.gameFramework.InputControllerInput
import com.corrodinggames.rts.gameFramework.utility.SlickToAndroidKeycodes
import io.github.rwpp.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeGameHotkeysRegressionTest {
    @Test
    fun nativeTargetedActionsRemainConfigurableThroughSavedKeyboardBindings() = withNativeKeyLabels {
        val input = InputController()
        val listed = NativeGameHotkeys.buttons(input)
        val actions = listOf(input.attackMoveButton, input.guardUnitButton, input.patrolButton, input.reclaimButton)
        actions.forEach { assertTrue(it in listed, "Native action ${it.displayName} must remain configurable") }
        val settings = Settings(hotkeyBindings = actions.mapIndexed { index, button ->
            button.settingsKey to "ALT+F${index + 1}"
        }.toMap())
        NativeGameHotkeys.apply(input, settings)
        actions.forEachIndexed { index, button -> assertBindings(button, input, "ALT+F${index + 1}") }
    }

    @Test
    fun tacticalAndPlanningLabelsExistInBothLanguages() {
        for (language in listOf("en", "zh")) {
            val resources = java.util.Properties().apply {
                java.nio.file.Files.newBufferedReader(
                    java.nio.file.Path.of("src/commonMain/composeResources/files/rwpp_$language.properties"),
                ).use { load(it) }
            }
            val translate: (String, Int?) -> String = { key, _ ->
                resources.getProperty(key)?.takeIf { it.isNotBlank() }
                    ?: error("Missing $language resource: $key")
            }
            for (action in listOf(
                "rwpp low health",
                "rwpp auto load",
                "rwpp building planning",
                "rwpp reclaim selection"
            )) {
                io.github.rwpp.logic.hotkeys.hotkeyActionLabel(action, translate)
            }
            translate("rwpp.planning.reclaim", null)
        }
    }

    @Test
    fun addedHotkeysPrecedeNativeControlGroupsWithoutDuplicateSelectAll() = withNativeKeyLabels {
        val input = InputController()
        val buttons = NativeGameHotkeys.buttons(input)
        val sameType = buttons.single { it.settingsKey == "rwpp_same_type" }
        val follow = buttons.single { it.settingsKey == "rwpp_follow_selection" }
        val names = buttons.map { it.displayName }

        assertBindings(sameType, input, "T")
        assertBindings(follow, input, "CTRL+F")
        val tacticalDefaults = mapOf(
            "rwpp_low_health" to "ALT+H",
            "rwpp_auto_load" to "ALT+L",
            "rwpp_building_planning" to "ALT+B",
            "rwpp_reclaim_selection" to "ALT+R",
        )
        tacticalDefaults.forEach { (key, binding) ->
            val button = buttons.single { it.settingsKey == key }
            assertBindings(button, input, binding)
            assertTrue(input.allButtons.indexOf(button) < input.allButtons.indexOf(input.unitGroupsHeader))
        }
        assertTrue("Select Whole Army" in names, "the native combat army command remains available")
        assertFalse("RWPP Select All" in names, "the redundant RWPP keyboard select-all command is removed")

        val headerIndex = input.allButtons.indexOf(input.unitGroupsHeader)
        val firstControlGroupIndex = input.allButtons.indexOf(input.createGroupButtons.first())
        assertTrue(input.allButtons.indexOf(sameType) in 0 until headerIndex)
        assertTrue(input.allButtons.indexOf(follow) in 0 until headerIndex)
        assertTrue(headerIndex < firstControlGroupIndex, "new hotkeys sit above the native control-group block")
    }

    @Test
    fun legacyDefaultMigratesOnceAndChatAlternateIsOnlySuppressedWhenUntouched() = withNativeKeyLabels {
        val input = InputController()
        val settings = Settings(hotkeyBindings = mapOf("rwpp_same_type" to "CTRL+T"))
        NativeGameHotkeys.apply(input, settings)

        val sameType = input.allButtons.filterIsInstance<InputControllerButton>()
            .single { it.settingsKey == "rwpp_same_type" }
        val sendChat = input.allButtons.filterIsInstance<InputControllerButton>()
            .single { it.settingsKey == "send_chat" }
        assertEquals(1, settings.hotkeyBindingsMigrationVersion)
        assertBindings(sameType, input, "T")
        assertBindings(sendChat, input, "ENTER")

        settings.hotkeyBindings = mapOf("rwpp_same_type" to "ALT+T")
        NativeGameHotkeys.apply(input, settings)
        assertBindings(sameType, input, "ALT+T")
        assertBindings(sendChat, input, "ENTER", "T")

        settings.hotkeyBindings = mapOf("rwpp_same_type" to "T", "send_chat" to "SHIFT+ENTER")
        NativeGameHotkeys.apply(input, settings)
        assertBindings(sendChat, input, "SHIFT+ENTER")

        settings.hotkeyBindings = mapOf("rwpp_same_type" to "CTRL+T")
        NativeGameHotkeys.apply(input, settings)
        assertBindings(sameType, input, "CTRL+T")
    }

    private fun assertBindings(button: InputControllerButton, input: InputController, vararg bindingNames: String) {
        val reference = input.createConfigurableButton("Regression expected binding")
        val expected = try {
            bindingNames.forEach { reference.addKeyBinding(it) }
            (0 until reference.inputBindings.size).mapNotNull(reference::getBinding)
        } finally {
            input.allButtons.remove(reference)
        }
        assertEquals(bindingNames.size, expected.size)
        assertEquals(bindingNames.size, button.inputBindings.size)
        expected.forEachIndexed { index, expectedBinding ->
            assertTrue(
                button.getBinding(index)?.matches(expectedBinding) == true,
                "binding $index should match native key '$bindingNames[index]'"
            )
            assertEquals(bindingNames[index].uppercase(), button.getBindingLabelAt(index))
        }
    }

    private inline fun <T> withNativeKeyLabels(block: () -> T): T {
        val previousInput = InputController.inputDevices
        InputController.inputDevices = NativeLabelInputDevices()
        return try {
            block()
        } finally {
            InputController.inputDevices = previousInput
        }
    }

    /** The ordinary JVM fixture returns <abstract>; mirror the platform's key labels without starting a game. */
    private class NativeLabelInputDevices : InputControllerInput() {
        override fun getKeyBindingLabel(keyCode: Int, modifierMask: Int): String {
            val modifiers = buildList {
                if (modifierMask and 1 != 0) add("CTRL")
                if (modifierMask and 2 != 0) add("SHIFT")
                if (modifierMask and 4 != 0) add("ALT")
            }
            return (modifiers + SlickToAndroidKeycodes.getAndroidKeyName(keyCode).uppercase()).joinToString("+")
        }
    }
}
