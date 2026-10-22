/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2.scrollbar.generic

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.rwpp.widget.v2.scrollbar.ScrollbarSelectionMode
import io.github.rwpp.widget.v2.scrollbar.ScrollbarSettings
import io.github.rwpp.widget.v2.scrollbar.controller.StateController
import io.github.rwpp.widget.v2.scrollbar.foundation.ScrollbarLayoutSettings

@Composable
internal fun <IndicatorValue> ElementScrollbar(
    orientation: Orientation,
    stateController: StateController<IndicatorValue>,
    modifier: Modifier,
    settings: ScrollbarSettings,
    indicatorContent: (@Composable (indicatorValue: IndicatorValue, isThumbSelected: Boolean) -> Unit)?,
) {
    val layoutSettings = remember(settings) {
        ScrollbarLayoutSettings(
            scrollbarPadding = settings.scrollbarPadding,
            thumbShape = settings.thumbShape,
            thumbThickness = settings.thumbThickness,
            thumbUnselectedColor = settings.thumbUnselectedColor,
            thumbSelectedColor = settings.thumbSelectedColor,
            side = settings.side,
            selectionActionable = settings.selectionActionable,
            hideEasingAnimation = settings.hideEasingAnimation,
            hideDisplacement = settings.hideDisplacement,
            hideDelayMillis = settings.hideDelayMillis,
            durationAnimationMillis = settings.durationAnimationMillis,
        )
    }

    BoxWithConstraints(modifier) {
        val maxLengthPixels = when (orientation) {
            Orientation.Vertical -> constraints.maxHeight
            Orientation.Horizontal -> constraints.maxWidth
        }.toFloat()

        ScrollbarLayout(
            orientation = orientation,
            thumbSizeNormalized = stateController.thumbSizeNormalized.value,
            thumbOffsetNormalized = stateController.thumbOffsetNormalized.value,
            thumbIsInAction = stateController.thumbIsInAction.value,
            thumbIsSelected = stateController.isSelected.value,
            settings = layoutSettings,
            indicator = indicatorContent?.let {
                { it(stateController.indicatorValue(), stateController.isSelected.value) }
            },
            draggableModifier = Modifier.draggable(
                state = rememberDraggableState { deltaPixel ->
                    stateController.onDraggableState(
                        deltaPixels = deltaPixel,
                        maxLengthPixels = maxLengthPixels
                    )
                },
                orientation = orientation,
                enabled = settings.selectionMode != ScrollbarSelectionMode.Disabled,
                startDragImmediately = true,
                onDragStarted = { offsetPixel ->
                    stateController.onDragStarted(
                        offsetPixels = when (orientation) {
                            Orientation.Horizontal -> offsetPixel.x
                            Orientation.Vertical -> offsetPixel.y
                        },
                        maxLengthPixels = maxLengthPixels
                    )
                },
                onDragStopped = {
                    stateController.onDragStopped()
                }
            )
        )
    }
}