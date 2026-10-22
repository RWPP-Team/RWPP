/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2.scrollbar.foundation

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import io.github.rwpp.widget.v2.scrollbar.ScrollbarLayoutSide
import io.github.rwpp.widget.v2.scrollbar.ScrollbarSelectionActionable

@Stable
internal data class ScrollbarLayoutSettings(
    val scrollbarPadding: Dp,
    val thumbShape: Shape,
    val thumbThickness: Dp,
    val thumbUnselectedColor: Color,
    val thumbSelectedColor: Color,
    val side: ScrollbarLayoutSide,
    val selectionActionable: ScrollbarSelectionActionable,
    val hideDisplacement: Dp,
    val hideDelayMillis: Int,
    val hideEasingAnimation: Easing,
    val durationAnimationMillis: Int
)