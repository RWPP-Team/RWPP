/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2.scrollbar.generic

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rwpp.widget.v2.scrollbar.foundation.HorizontalScrollbarLayout
import io.github.rwpp.widget.v2.scrollbar.foundation.ScrollbarLayoutSettings
import io.github.rwpp.widget.v2.scrollbar.foundation.VerticalScrollbarLayout

@Composable
internal fun ScrollbarLayout(
    orientation: Orientation,
    thumbSizeNormalized: Float,
    thumbOffsetNormalized: Float,
    thumbIsInAction: Boolean,
    thumbIsSelected: Boolean,
    settings: ScrollbarLayoutSettings,
    draggableModifier: Modifier,
    indicator: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when (orientation) {
        Orientation.Vertical -> VerticalScrollbarLayout(
            thumbSizeNormalized = thumbSizeNormalized,
            thumbOffsetNormalized = thumbOffsetNormalized,
            thumbIsInAction = thumbIsInAction,
            thumbIsSelected = thumbIsSelected,
            settings = settings,
            draggableModifier = draggableModifier,
            indicator = indicator,
            modifier = modifier,
        )

        Orientation.Horizontal -> HorizontalScrollbarLayout(
            thumbSizeNormalized = thumbSizeNormalized,
            thumbOffsetNormalized = thumbOffsetNormalized,
            thumbIsInAction = thumbIsInAction,
            thumbIsSelected = thumbIsSelected,
            settings = settings,
            draggableModifier = draggableModifier,
            indicator = indicator,
            modifier = modifier,
        )
    }
}