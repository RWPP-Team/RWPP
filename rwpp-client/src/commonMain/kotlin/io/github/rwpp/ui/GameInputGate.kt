/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable

/**
 * Native game input must be discarded while a non-game screen owns the navigator.
 * The navigator is published from App once the first composition completes, so the
 * lookup stays guarded for the boot frames that arrive before that assignment.
 */
fun gameInputBlockedByNavigator(): Boolean =
    UI.liveNavigator?.current?.let { it !is GameScreen } ?: false

/** Any gameplay screen in the stack owns the window, not only the top screen. */
fun navigatorHoldsGameScreen(): Boolean =
    UI.liveNavigator?.backStack?.any { it is GameScreen } ?: false

/**
 * Reads the stack inside composition so a settings dialog pushed over gameplay keeps
 * the background layer hidden. The wallpaper is drawn in the window layer, which sits
 * above the native game surface and therefore occludes it while any GameScreen lives.
 */
@Composable
internal fun rememberNavigatorHoldsGameScreen(): Boolean {
    val navigator = UI.liveNavigator ?: return false
    return navigator.backStack.any { it is GameScreen }
}
