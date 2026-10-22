/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager

enum class WindowManager {
    Small, Middle, Large
}

@Composable
fun generalProportion(): Float {
    val manager = LocalWindowManager.current
    return when(manager) {
        WindowManager.Small -> 0.8f
        WindowManager.Middle -> 0.65f
        WindowManager.Large -> 0.5f
    }
}

@Composable
fun largeProportion(): Float {
    val manager = LocalWindowManager.current
    return when(manager) {
        WindowManager.Small -> 0.95f
        WindowManager.Middle -> 0.8f
        WindowManager.Large -> 0.65f
    }
}

@Composable
fun scaleFitFloat(): Float {
    val manager = LocalWindowManager.current
    return when(manager) {
        WindowManager.Small -> 0.7f
        WindowManager.Middle -> 0.8f
        WindowManager.Large -> 1f
    }
}

@Composable
fun Modifier.scaleFit(): Modifier {
    return this.scale(scaleFitFloat())
}

@Composable
fun constraintWindowManager(width: Dp, height: Dp): WindowManager {
    return when {
        width >= 1366.dp && height >= 768.dp -> WindowManager.Large
        width >= 700.dp && height >= 500.dp -> WindowManager.Middle
        else -> WindowManager.Small
    }
}