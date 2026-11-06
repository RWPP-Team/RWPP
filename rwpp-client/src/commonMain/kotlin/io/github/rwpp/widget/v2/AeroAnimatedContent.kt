/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val ANIMATION_TIME = 150L
internal const val DIALOG_BUILD_TIME = 16L

/** Dialog motion follows the shared animation switch; disabled keeps the panel but drops it. */
internal fun aeroDialogEffectsEnabled(): Boolean =
    runCatching { io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>().enableAnimations }
        .getOrDefault(true)

/** Dismiss delay matching the active transition, so a disabled effect closes immediately. */
internal fun aeroDialogEffectMillis(): Long = if (aeroDialogEffectsEnabled()) ANIMATION_TIME else 0L

@Suppress("UnusedTransitionTargetStateParameter")
@Composable
private fun SciFiContentContainer(
    visible: Boolean,
    isDanger: Boolean,
    content: @Composable () -> Unit
) {
    var contentSize by remember { mutableStateOf(Size.Zero) }
    val effects = aeroDialogEffectsEnabled()

    val primaryColor = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val primaryContainerColor =
        if (isDanger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val coreColor = MaterialTheme.colorScheme.inverseOnSurface

    val transition = updateTransition(targetState = visible, label = "SciFiTransition")

    val progress by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = ANIMATION_TIME.toInt(), easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = ANIMATION_TIME.toInt(), easing = FastOutLinearInEasing)
            }
        },
        label = "progress"
    ) { if (it) 1f else 0f }

    val scaleX by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    0f at 0
                    1f at (ANIMATION_TIME * 0.3f).toInt() using FastOutSlowInEasing
                    1f at ANIMATION_TIME.toInt()
                }
            } else {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    1f at 0
                    1f at (ANIMATION_TIME * 0.6f).toInt()
                    0f at ANIMATION_TIME.toInt() using FastOutLinearInEasing
                }
            }
        },
        label = "scaleX"
    ) { if (it) 1f else 0f }

    val scaleY by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    0.005f at 0
                    0.01f at (ANIMATION_TIME * 0.25f).toInt()
                    1f at ANIMATION_TIME.toInt() using FastOutSlowInEasing
                }
            } else {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    1f at 0
                    0.01f at (ANIMATION_TIME * 0.7f).toInt() using FastOutSlowInEasing
                    0f at ANIMATION_TIME.toInt()
                }
            }
        },
        label = "scaleY"
    ) { if (it) 1f else 0f }

    val laserIntensity by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    0.5f at 0
                    1f at (ANIMATION_TIME * 0.3f).toInt()
                    0f at ANIMATION_TIME.toInt()
                }
            } else {
                keyframes {
                    durationMillis = ANIMATION_TIME.toInt()
                    0f at 0
                    1f at (ANIMATION_TIME * 0.7f).toInt()
                    0.5f at ANIMATION_TIME.toInt()
                }
            }
        },
        label = "laserIntensity"
    ) { 0f }

    val presentedScaleX = if (effects) scaleX else if (visible) 1f else 0f
    val presentedScaleY = if (effects) scaleY else if (visible) 1f else 0f
    val presentedAlpha = if (effects) progress else if (visible) 1f else 0f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .onGloballyPositioned { contentSize = it.size.toSize() }
            .graphicsLayer {
                this.scaleX = presentedScaleX
                this.scaleY = presentedScaleY
                this.alpha = if (presentedAlpha > 0.01f) 1f else 0f
            }
    ) {
        content()

        if (effects && contentSize.width > 0f && contentSize.height > 0f && laserIntensity > 0f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val centerY = size.height / 2f
                val beamHeight = (size.height * 0.45f * laserIntensity).coerceAtLeast(8f)

                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            coreColor.copy(alpha = laserIntensity),
                            primaryColor.copy(alpha = 0.95f * laserIntensity),
                            primaryContainerColor.copy(alpha = 0.6f * laserIntensity),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, centerY),
                        radius = size.width * 0.65f
                    ),
                    blendMode = BlendMode.Screen
                )

                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryColor.copy(alpha = 0.95f * laserIntensity),
                            coreColor.copy(alpha = laserIntensity),
                            primaryColor.copy(alpha = 0.95f * laserIntensity),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(0f, centerY - beamHeight / 2f),
                    size = Size(size.width, beamHeight),
                    blendMode = BlendMode.Screen
                )
            }
        }
    }
}

@Composable
fun AnimatedTransitionDialog(
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean,
    isDanger: Boolean,
    contentAlignment: Alignment = Alignment.Center,
    onDismissStarted: () -> Unit = {},
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit
) {
    val onDismissSharedFlow: MutableSharedFlow<Any> = remember { MutableSharedFlow() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    val animateTrigger = remember { mutableStateOf(false) }

    LaunchedEffect(key1 = Unit) {
        launch {
            delay(DIALOG_BUILD_TIME.milliseconds)
            animateTrigger.value = true
        }
        launch {
            onDismissSharedFlow.asSharedFlow().collectLatest {
                startDismissWithExitAnimation(animateTrigger, onDismissRequest)
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (enableDismiss) {
                onDismissStarted()
                coroutineScope.launch {
                    startDismissWithExitAnimation(animateTrigger, onDismissRequest)
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            contentAlignment = contentAlignment,
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            SciFiContentContainer(
                visible = animateTrigger.value,
                isDanger = isDanger
            ) {
                content(AnimatedTransitionDialogHelper(coroutineScope, onDismissSharedFlow, onDismissStarted))
            }
        }
    }
}

@Composable
fun AnimatedDialog(
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    isDanger: Boolean = false,
    onDismissStarted: () -> Unit = {},
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    AnimatedTransitionDialog(
        onDismissRequest = onDismissRequest,
        enableDismiss = enableDismiss,
        isDanger = isDanger,
        onDismissStarted = onDismissStarted,
    ) { helper ->
        content(helper::triggerAnimatedDismiss)
    }
}

@Composable
fun AnimatedAeroPopup(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    popupPositionProvider: PopupPositionProvider,
    focusable: Boolean = true,
    enableDismiss: Boolean = true,
    isDanger: Boolean = false,
    onDismissStarted: () -> Unit = {},
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissStarted by rememberUpdatedState(onDismissStarted)
    val currentEnableDismiss by rememberUpdatedState(enableDismiss)
    var popupVisible by remember { mutableStateOf(false) }
    var animatedVisible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    val requestDismiss = {
        if (currentEnableDismiss && popupVisible && !dismissing) {
            dismissing = true
            currentOnDismissStarted()
            animatedVisible = false
            coroutineScope.launch {
                delay(aeroDialogEffectMillis().milliseconds)
                popupVisible = false
                dismissing = false
                currentOnDismissRequest()
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            dismissing = false
            popupVisible = true
            delay(DIALOG_BUILD_TIME.milliseconds)
            animatedVisible = true
        } else if (popupVisible) {
            dismissing = true
            animatedVisible = false
            delay(aeroDialogEffectMillis().milliseconds)
            popupVisible = false
            dismissing = false
        }
    }

    if (popupVisible) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = requestDismiss,
            properties = PopupProperties(
                focusable = focusable,
                dismissOnBackPress = enableDismiss && focusable,
                dismissOnClickOutside = enableDismiss,
            )
        ) {
            Box(contentAlignment = contentAlignment) {
                SciFiContentContainer(
                    visible = animatedVisible,
                    isDanger = isDanger
                ) {
                    content(requestDismiss)
                }
            }
        }
    }
}

class AnimatedTransitionDialogHelper(
    private val coroutineScope: CoroutineScope,
    private val onDismissFlow: MutableSharedFlow<Any>,
    private val onDismissStarted: () -> Unit,
) {
    fun triggerAnimatedDismiss() {
        onDismissStarted()
        coroutineScope.launch {
            onDismissFlow.emit(Any())
        }
    }
}

suspend fun startDismissWithExitAnimation(
    animateTrigger: MutableState<Boolean>,
    onDismissRequest: () -> Unit
) {
    animateTrigger.value = false
    delay(aeroDialogEffectMillis().milliseconds)
    onDismissRequest()
}

@Composable
fun AnimatedAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    isDanger: Boolean = false,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        enableDismiss = enableDismiss,
        isDanger = isDanger,
        onDismissStarted = {},
        content = content,
    )
}

@Composable
fun AnimatedAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    isDanger: Boolean = false,
    onDismissStarted: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    if (visible) {
        AnimatedDialog(
            onDismissRequest = onDismissRequest,
            enableDismiss = enableDismiss,
            isDanger = isDanger,
            onDismissStarted = onDismissStarted,
            content = content,
        )
    }
}

context(scope: LazyItemScope)
fun Modifier.aeroAnimateItem(): Modifier = with(scope) {
    this@aeroAnimateItem.animateItem(
        fadeInSpec = keyframes {
            durationMillis = 220
            0f at 0
            1f at 40 using FastOutLinearInEasing
            0.25f at 90 using LinearEasing
            1f at 160 using FastOutSlowInEasing
        },
        placementSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy,
            visibilityThreshold = IntOffset.VisibilityThreshold
        ),
        fadeOutSpec = keyframes {
            durationMillis = 140
            1f at 0
            0.15f at 40 using FastOutLinearInEasing
            0.65f at 80
            0f at 140 using LinearEasing
        }
    )
}
