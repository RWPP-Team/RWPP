/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import io.github.rwpp.config.Settings
import org.koin.compose.koinInject

enum class ButtonState { Pressed, Idle }

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = composed {
    if (koinInject<Settings>().enableAnimations) {
        var buttonState by remember { mutableStateOf(ButtonState.Idle) }
        val scale by animateFloatAsState(if (buttonState == ButtonState.Pressed) 0.70f else 1f)

        this
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .pointerInput(buttonState) {
                awaitPointerEventScope {
                    buttonState = if (buttonState == ButtonState.Pressed) {
                        waitForUpOrCancellation()
                        ButtonState.Idle
                    } else {
                        awaitFirstDown(false)
                        ButtonState.Pressed
                    }
                }
            }
    } else {
        this.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}