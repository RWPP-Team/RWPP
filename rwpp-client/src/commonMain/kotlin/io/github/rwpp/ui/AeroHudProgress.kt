/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
internal fun AeroHudProgress(progress: () -> Float, modifier: Modifier, color: Color, trackColor: Color) {
    Canvas(modifier) {
        drawRect(trackColor)
        drawRect(color, size = Size(size.width * progress().coerceIn(0f, 1f), size.height))
    }
}
