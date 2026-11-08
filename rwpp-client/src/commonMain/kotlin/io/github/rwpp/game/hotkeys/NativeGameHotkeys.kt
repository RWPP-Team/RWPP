/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.InputController
import com.corrodinggames.rts.gameFramework.InputControllerButton
import io.github.rwpp.config.Settings
import io.github.rwpp.game.building.BuildingPlanningController
import io.github.rwpp.logic.hotkeys.hotkeyBindingSlots
import io.github.rwpp.logic.hotkeys.normalizedHotkeyBindings
import io.github.rwpp.logic.hotkeys.validHotkeyBindings
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Native controller ownership and unit actions; Compose state is supplied by the UI caller. */
internal object NativeGameHotkeys {
    private var owner: InputController? = null
    private var applied: Map<String, String>? = null
    private lateinit var sameTypeButton: InputControllerButton
    private lateinit var followSelectionButton: InputControllerButton
    private lateinit var lowHealthButton: InputControllerButton
    private lateinit var autoLoadButton: InputControllerButton
    private lateinit var buildingPlanningButton: InputControllerButton
    private lateinit var reclaimSelectionButton: InputControllerButton
    private var nativeChatDefaultTWasSuppressed = false

    private val nativeKeyNames by lazy {
        // Read native key constants without initializing the controller or platform logger.
        Class.forName("com.corrodinggames.rts.gameFramework.utility.SlickToAndroidKeycodes\$AndroidCodes")
            .fields.map {
                it.name.replace("KEYCODE_", "").replace("KEY_", "")
                    .replace("NUMPAD_", "NUMPAD").replace("NUM_", "NUMPAD")
            }.toSet()
    }

    fun buttons(): List<InputControllerButton> {
        return buttons(GameEngine.getInstance().inputController)
    }

    /** Overload kept internal so JVM regressions can exercise ordering against a native controller fixture. */
    internal fun buttons(input: InputController): List<InputControllerButton> {
        if (owner !== input) {
            owner = input
            sameTypeButton = input.createConfigurableButton("RWPP Same Type").apply { addKeyBinding("T") }
            followSelectionButton =
                input.createConfigurableButton("RWPP Follow Selection").apply { addKeyBinding("CTRL+F") }
            lowHealthButton = input.createConfigurableButton("RWPP Low Health").apply { addKeyBinding("ALT+H") }
            autoLoadButton = input.createConfigurableButton("RWPP Auto Load").apply { addKeyBinding("ALT+L") }
            buildingPlanningButton =
                input.createConfigurableButton("RWPP Building Planning").apply { addKeyBinding("ALT+B") }
            reclaimSelectionButton =
                input.createConfigurableButton("RWPP Reclaim Selection").apply { addKeyBinding("ALT+R") }
            placeBeforeControlGroupBlock(
                input, listOf(
                    sameTypeButton, followSelectionButton,
                    lowHealthButton, autoLoadButton, buildingPlanningButton, reclaimSelectionButton
                )
            )
            applied = null
            nativeChatDefaultTWasSuppressed = false
        }
        return input.allButtons.filterIsInstance<InputControllerButton>().filter { it.configurable && !it.isHeader }
    }

    fun apply(settings: Settings) {
        apply(GameEngine.getInstance().inputController, settings)
    }

    /** Native-controller overload allows deterministic tests without initializing the game singleton. */
    internal fun apply(input: InputController, settings: Settings) {
        migrateLegacyHotkeyDefaults(settings)
        // Avoid walking the complete native button list every game frame. The list only needs
        // rebuilding when the engine replaces its controller or the user changes bindings.
        if (owner === input && applied == settings.hotkeyBindings) return
        val buttons = buttons(input)
        buttons.forEach { button ->
            settings.hotkeyBindings[button.settingsKey]?.takeIf(::validNativeHotkeyBindings)?.let { value ->
                button.inputBindings.clear()
                normalizedHotkeyBindings(value).split(',').filter(String::isNotBlank)
                    .forEach { button.addKeyBinding(it) }
            }
        }
        reconcileDefaultSendChatBinding(input, settings)
        applied = settings.hotkeyBindings.toMap()
    }

    fun update(settings: Settings, blocked: Boolean) {
        apply(settings)
        NativeBuilderGroupAssistance.update(settings, blocked)
        BuildingPlanningController.onGameUpdate(blocked)
        val engine = GameEngine.getInstance()
        if (!blocked && followSelectionButton.isPressed) toggleFollowSelection(engine)
        SelectionCameraFollowController.updateOnGameThread(engine, blocked || BuildingPlanningController.ownsIndependentPlanningInput())
        if (blocked) return
        if (sameTypeButton.isPressed) selectSameType(engine)
        if (lowHealthButton.isPressed) NativeTacticalActions.selectLowHealth(engine)
        if (autoLoadButton.isPressed) NativeTacticalActions.autoLoad(engine)
        if (buildingPlanningButton.isPressed) BuildingPlanningController.toggleMode()
        if (reclaimSelectionButton.isPressed) BuildingPlanningController.beginReclaimSelection()
    }

    /** Reuses the native command path for both keyboard and Android HUD controls. */
    fun selectAll(engine: GameEngine = GameEngine.getInstance()) {
        val ui = engine.interfaceEngine
        ui.cancelActiveAction()
        ui.clearUnitSelection()
        GameUnit.unitList.filterIsInstance<OrderableUnit>().filter {
            !it.dead && it.transportedBy == null && ui.canPlayerControlUnit(it)
        }.forEach { ui.selectUnit(it) }
    }

    /** Reuses InterfaceEngine.selectAllUnitsOfType for both keyboard and Android HUD controls. */
    fun selectSameType(engine: GameEngine = GameEngine.getInstance()) {
        val selected = engine.interfaceEngine.selectedUnits.filterIsInstance<GameUnit>().firstOrNull() ?: return
        engine.interfaceEngine.selectAllUnitsOfType(selected)
    }

    fun toggleFollowSelection(engine: GameEngine = GameEngine.getInstance()) {
        SelectionCameraFollowController.toggleOnGameThread(engine)
    }

    fun validNativeHotkeyBindings(value: String): Boolean =
        validHotkeyBindings(value, nativeKeyNames)

    fun bindingSlots(labels: List<String>): List<String> =
        hotkeyBindingSlots(labels, ::validNativeHotkeyBindings)

    private fun migrateLegacyHotkeyDefaults(settings: Settings) {
        if (settings.hotkeyBindingsMigrationVersion >= 1) return
        val legacySameType = settings.hotkeyBindings["rwpp_same_type"]
        if (legacySameType != null && normalizedHotkeyBindings(legacySameType) == "CTRL+T") {
            settings.hotkeyBindings = settings.hotkeyBindings + ("rwpp_same_type" to "T")
        }
        settings.hotkeyBindingsMigrationVersion = 1
    }

    private fun reconcileDefaultSendChatBinding(input: InputController, settings: Settings) {
        val sendChat = input.allButtons.filterIsInstance<InputControllerButton>()
            .firstOrNull { it.settingsKey == "send_chat" } ?: return
        // A binding explicitly saved in RWPP settings is a user choice; leave it alone.
        if (settings.hotkeyBindings.containsKey(sendChat.settingsKey)) {
            nativeChatDefaultTWasSuppressed = false
            return
        }
        val sameTypeBindings = settings.hotkeyBindings["rwpp_same_type"] ?: "T"
        val sameTypeUsesT = normalizedHotkeyBindings(sameTypeBindings).split(',').any { it == "T" }
        val currentChatBindings = (0 until sendChat.inputBindings.size).map(sendChat::getBindingLabelAt)
        if (sameTypeUsesT && currentChatBindings == listOf("ENTER", "T")) {
            sendChat.inputBindings.clear()
            sendChat.addKeyBinding("ENTER")
            nativeChatDefaultTWasSuppressed = true
        } else if (!sameTypeUsesT && nativeChatDefaultTWasSuppressed && currentChatBindings == listOf("ENTER")) {
            sendChat.inputBindings.clear()
            sendChat.addKeyBinding("ENTER").addKeyBinding("T")
            nativeChatDefaultTWasSuppressed = false
        }
    }

    private fun placeBeforeControlGroupBlock(input: InputController, buttons: List<InputControllerButton>) {
        val nativeButtons = input.allButtons
        buttons.forEach { nativeButtons.remove(it) }
        val groupHeaderIndex = nativeButtons.indexOf(input.unitGroupsHeader)
            .takeIf { it >= 0 } ?: nativeButtons.size
        buttons.forEachIndexed { offset, button -> nativeButtons.add(groupHeaderIndex + offset, button) }
    }
}
