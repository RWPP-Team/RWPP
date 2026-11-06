/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.InputControllerButton
import io.github.rwpp.api.coroutines.GameMainDispatcher
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hotkeys.NativeGameHotkeys
import io.github.rwpp.logic.hotkeys.hotkeyActionLabel
import io.github.rwpp.widget.v2.AeroSciFiTheme
import io.github.rwpp.widget.v2.AeroSettingGroup
import io.github.rwpp.widget.v2.AeroTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val captureKeys: Map<Key, String> = buildMap {
    listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z
    )
        .forEachIndexed { index, key -> put(key, ('A' + index).toString()) }
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .forEachIndexed { index, key -> put(key, index.toString()) }
    listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
        .forEachIndexed { index, key -> put(key, "F${index + 1}") }
    listOf(
        Key.NumPad0,
        Key.NumPad1,
        Key.NumPad2,
        Key.NumPad3,
        Key.NumPad4,
        Key.NumPad5,
        Key.NumPad6,
        Key.NumPad7,
        Key.NumPad8,
        Key.NumPad9
    )
        .forEachIndexed { index, key -> put(key, "NUMPAD$index") }
    put(Key.Spacebar, "SPACE"); put(Key.Enter, "ENTER"); put(Key.NumPadEnter, "NUMPADENTER")
    put(Key.Tab, "TAB"); put(Key.Backspace, "DEL"); put(Key.Delete, "FORWARD_DEL")
    put(Key.DirectionUp, "DPAD_UP"); put(Key.DirectionDown, "DPAD_DOWN")
    put(Key.DirectionLeft, "DPAD_LEFT"); put(Key.DirectionRight, "DPAD_RIGHT")
    put(Key.MoveHome, "MOVE_HOME"); put(Key.MoveEnd, "MOVE_END"); put(Key.Insert, "INSERT")
    put(Key.PageUp, "PAGE_UP"); put(Key.PageDown, "PAGE_DOWN")
    put(Key.Minus, "MINUS"); put(Key.Equals, "EQUALS"); put(Key.Comma, "COMMA"); put(Key.Period, "PERIOD")
    put(Key.Slash, "SLASH"); put(Key.Semicolon, "SEMICOLON"); put(Key.Apostrophe, "APOSTROPHE")
    put(Key.Backslash, "BACKSLASH"); put(Key.Grave, "GRAVE")
}

internal fun capturedHotkey(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): String? {
    val name = captureKeys[key] ?: return null
    val binding = listOfNotNull(
        if (ctrl) "CTRL" else null, if (shift) "SHIFT" else null,
        if (alt) "ALT" else null, name
    ).joinToString("+")
    return binding.takeIf(NativeGameHotkeys::validNativeHotkeyBindings)
}

@Composable
internal fun HotkeySettings(settings: Settings) {
    var buttons by remember { mutableStateOf(emptyList<InputControllerButton>()) }
    LaunchedEffect(Unit) {
        buttons = withContext(GameMainDispatcher) {
            NativeGameHotkeys.buttons().also { NativeGameHotkeys.apply(settings) }
        }
    }
    MouseControlPreferences(settings)
    var search by remember { mutableStateOf("") }
    AeroSettingGroup("HOTKEYS", "KEYBOARD") {
        Text(
            readI18n("rwpp.hotkeys.hint"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
        )
        AeroTextField(search, { search = it }, readI18n("rwpp.hotkeys.search"), Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(readI18n("rwpp.hotkeys.action"), Modifier.weight(1f))
            Text(readI18n("rwpp.hotkeys.binding1"), Modifier.width(140.dp))
            Text(readI18n("rwpp.hotkeys.binding2"), Modifier.width(140.dp))
        }
        buttons.filter {
            search.isBlank() || hotkeyActionLabel(it.displayName).contains(search, true) ||
                    it.displayName.contains(search, true)
        }.forEach { button ->
            key(button.settingsKey) {
                val initial = settings.hotkeyBindings[button.settingsKey]?.split(',')
                    ?: button.inputBindings.indices.map { button.getBindingLabelAt(it) }
                var bindings by remember { mutableStateOf(NativeGameHotkeys.bindingSlots(initial)) }
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(hotkeyActionLabel(button.displayName), Modifier.weight(1f), fontSize = 12.sp)
                    repeat(2) { slot ->
                        fun save(value: String) {
                            bindings = bindings.toMutableList().also { it[slot] = value }
                            settings.hotkeyBindings += (button.settingsKey to bindings.filter(String::isNotBlank)
                                .joinToString(","))
                            GameScope.launch { NativeGameHotkeys.apply(settings) }
                        }
                        HotkeyBindingField(
                            binding = bindings[slot],
                            prompt = readI18n("rwpp.hotkeys.pressKey"),
                            modifier = Modifier.width(132.dp),
                            onBindingChanged = ::save,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A shortcut field is not a text input: it never accepts characters, it only turns
 * the next key combination into a binding. A real text field routes keys through the
 * text input session first, so this control owns its own focusable node and reads the
 * key events directly.
 */
@Composable
internal fun HotkeyBindingField(
    binding: String,
    prompt: String,
    modifier: Modifier = Modifier,
    onBindingChanged: (String) -> Unit,
    onCaptureChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }
    val accentColor = MaterialTheme.colorScheme.primary

    fun endCapture() {
        if (focused) {
            focused = false
            onCaptureChanged(false)
        }
        focusManager.clearFocus()
    }

    fun save(value: String) {
        onBindingChanged(value)
        endCapture()
    }

    fun capture(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return true
        when (event.key) {
            Key.Escape -> endCapture()
            Key.Delete if !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed -> save("")
            else -> capturedHotkey(
                event.key,
                event.isCtrlPressed,
                event.isShiftPressed,
                event.isAltPressed,
            )?.let(::save)
        }
        return true
    }

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(
                if (focused) accentColor.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .border(1.dp, if (focused) accentColor else MaterialTheme.colorScheme.outlineVariant, shape)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (focused != state.isFocused) {
                    focused = state.isFocused
                    onCaptureChanged(state.isFocused)
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent(::capture)
            .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = if (focused) prompt else binding.ifBlank { "—" },
            color = if (focused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
