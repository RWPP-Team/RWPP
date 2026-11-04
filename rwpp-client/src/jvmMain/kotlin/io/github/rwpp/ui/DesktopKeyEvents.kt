/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package io.github.rwpp.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent

private val nativeKeySource = object : Component() {}

/** Desktop Foundation distinguishes typed characters by their native AWT event, not codePoint. */
internal fun desktopComposeKeyEvent(
    key: Key, type: KeyEventType, character: Char,
    ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false
): KeyEvent {
    val printable = !character.isISOControl() && character != AwtKeyEvent.CHAR_UNDEFINED
    val typed = type == KeyEventType.KeyDown && printable && !ctrl && !alt
    val modifiers = (if (ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
            (if (alt) InputEvent.ALT_DOWN_MASK else 0) or (if (shift) InputEvent.SHIFT_DOWN_MASK else 0)
    val native = AwtKeyEvent(
        nativeKeySource,
        if (typed) AwtKeyEvent.KEY_TYPED else if (type == KeyEventType.KeyUp) AwtKeyEvent.KEY_RELEASED else AwtKeyEvent.KEY_PRESSED,
        System.currentTimeMillis(), modifiers,
        if (typed) AwtKeyEvent.VK_UNDEFINED else key.keyCode.toInt(),
        if (printable) character else AwtKeyEvent.CHAR_UNDEFINED
    )
    return KeyEvent(
        key, type, codePoint = if (printable) character.code else 0,
        isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift, nativeEvent = native
    )
}
