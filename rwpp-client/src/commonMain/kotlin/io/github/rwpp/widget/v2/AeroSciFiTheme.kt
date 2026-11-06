/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.rwpp.api.game.audio.GameSound
import io.github.rwpp.sound.Sounds
import io.github.rwpp.ui.hudPanelAlpha
import kotlin.random.Random

object AeroSciFiTheme {
    class ChamferAeroShape(private val cutSize: Float = 16f) : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density
        ): Outline {
            val path = Path().apply {
                moveTo(cutSize, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height - cutSize)
                lineTo(size.width - cutSize, size.height)
                lineTo(0f, size.height)
                lineTo(0f, cutSize)
                close()
            }
            return Outline.Generic(path)
        }
    }
}

expect fun Modifier.skiaGlitchEffect(
    progress: Float,
    time: Float
): Modifier

private val LocalAeroScaleApplied = staticCompositionLocalOf { false }

/** Snapshot-backed setting so the root container reacts immediately to the Settings slider. */
object AeroUiScalePreference {
    var multiplier by mutableFloatStateOf(1f)
}

@Composable
fun AeroScaledContainer(
    modifier: Modifier = Modifier,
    targetWidth: Dp = 1280.dp,
    targetHeight: Dp = 720.dp,
    userScale: Float = 1f,
    content: @Composable () -> Unit
) {
    if (LocalAeroScaleApplied.current) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = { content() }
        )
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val currentDensity = LocalDensity.current
        val scaleX = maxWidth / targetWidth
        val scaleY = maxHeight / targetHeight
        val fitScale = minOf(scaleX, scaleY)
        // Small windows retain normal text size and reflow/scroll. Roomy windows scale the
        // logical density down so the full screen shows more UI instead of enlarging every dp.
        val fitScaleDown = if (fitScale <= 1f) 1f else (1f / fitScale).coerceAtLeast(0.78f)
        val scale = (fitScaleDown * userScale.coerceIn(0.6f, 1.5f)).coerceIn(0.45f, 1.5f)
        val contentWidth = maxWidth / scale
        val contentHeight = maxHeight / scale

        val scaledDensity = Density(
            density = currentDensity.density * scale,
            fontScale = currentDensity.fontScale
        )

        CompositionLocalProvider(
            LocalDensity provides scaledDensity,
            LocalAeroScaleApplied provides true
        ) {
            Box(
                modifier = Modifier.size(contentWidth, contentHeight),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
fun AeroPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(20f) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = hudPanelAlpha(0.85f)),
                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = hudPanelAlpha(0.95f))
                    )
                )
            )
            .border(1.dp, borderColor, shape)
            .padding(1.dp),
        content = content
    )
}

@Composable
fun AeroHeader(
    title: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    color: Color = MaterialTheme.colorScheme.primary
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleStyle = TextStyle(
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
        )
        val tagStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        val titleWidth = measurer.measure(title, style = titleStyle, maxLines = 1, softWrap = false).size.width
        val tagWidth =
            tag?.let { measurer.measure(it, style = tagStyle, maxLines = 1, softWrap = false).size.width } ?: 0
        val showTag = tag != null && (!constraints.hasBoundedWidth ||
                titleWidth + tagWidth + with(density) { 24.dp.roundToPx() } <= constraints.maxWidth)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp, 16.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = titleStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showTag) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = tag!!, style = tagStyle, maxLines = 1, softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun AeroHeaderWithCloseButton(
    title: String,
    tag: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    onDismissRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AeroHeader(
            title = title,
            tag = tag,
            color = color,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(12.dp))

        AeroIconButton(
            painter = rememberVectorPainter(Icons.Default.Close),
            size = 32.dp,
            iconSize = 16.dp,
            isDanger = true,
            clickSound = Sounds.closePopup,
            onClick = onDismissRequest
        )
    }
}

@Composable
fun AeroMenuButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subTitle: String? = null,
    iconPainter: Painter? = null,
    iconVector: ImageVector? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.96f
            isHovered -> 1.02f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "menuBtnScale"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> accentColor.copy(alpha = 0.35f)
            isHovered -> accentColor.copy(alpha = 0.2f)
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        },
        animationSpec = tween(150),
        label = "menuBtnBg"
    )

    val animBorderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            isHovered -> accentColor
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(150),
        label = "menuBtnBorder"
    )

    val shape = remember { AeroSciFiTheme.ChamferAeroShape(12f) }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(Sounds.openPopup)
                    onClick()
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (iconPainter != null) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = if (isPressed) Color.White else if (isHovered) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = if (isPressed) Color.White else if (isHovered) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        color = if (isPressed) Color.White else if (isHovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = if (isHovered || isPressed) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.sp
                    )

                    if (subTitle != null) {
                        Text(
                            text = subTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isPressed) Color.White else if (isHovered) accentColor else Color.Transparent)
            )
        }
    }
}


@Composable
fun AeroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
    accentColor: Color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    clickSound: GameSound? = Sounds.select,
    subText: String? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (enabled && isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isHovered -> 1.02f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "btnScale"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> accentColor.copy(alpha = 0.35f)
            isHovered -> accentColor.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        },
        animationSpec = tween(120),
        label = "btnBg"
    )

    val animBorderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            isHovered -> accentColor
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(120),
        label = "btnBorder"
    )

    val shape = remember { AeroSciFiTheme.ChamferAeroShape(10f) }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.4f
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(clickSound)
                    onClick()
                }
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (isHovered || isPressed) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(if (isPressed) 0.8f else 0.6f)
                    .align(Alignment.CenterStart)
                    .background(if (isPressed) Color.White else accentColor, RoundedCornerShape(2.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (isHovered || isPressed) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                color = if (isPressed) Color.White else if (isHovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = if (isHovered || isPressed) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 1.sp
            )

            if (subText != null) {
                Text(
                    text = subText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun AeroToggleTile(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val activeColor = MaterialTheme.colorScheme.primary

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isHovered -> 1.02f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "toggleScale"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> activeColor.copy(alpha = 0.35f)
            checked -> activeColor.copy(alpha = if (isHovered) 0.25f else 0.15f)
            isHovered -> activeColor.copy(alpha = 0.1f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(150),
        label = "toggleBg"
    )

    val animBorderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            checked -> activeColor.copy(alpha = if (isHovered) 0.9f else 0.6f)
            isHovered -> activeColor.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(150),
        label = "toggleBorder"
    )

    val shape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(if (checked) Sounds.cancel else Sounds.select)
                    onCheckedChange(!checked)
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isPressed) Color.White else if (checked || isHovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (checked) activeColor else MaterialTheme.colorScheme.outline)
        )
    }
}

@Composable
fun AeroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    val normalizedValue = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    val animValue by animateFloatAsState(
        targetValue = normalizedValue,
        animationSpec = tween(60),
        label = "sliderAnim"
    )

    val shape = remember { AeroSciFiTheme.ChamferAeroShape(4f) }

    Box(
        modifier = modifier
            .height(26.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .onGloballyPositioned { widthPx = it.size.width.toFloat() }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    if (widthPx > 0f) {
                        val newNorm = (offset.x / widthPx).coerceIn(0f, 1f)
                        val newValue = valueRange.start + newNorm * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    if (widthPx > 0f) {
                        val newNorm = (change.position.x / widthPx).coerceIn(0f, 1f)
                        val newValue = valueRange.start + newNorm * (valueRange.endInclusive - valueRange.start)
                        onValueChange(newValue)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = size.height
            val trackWidth = size.width
            val filledWidth = trackWidth * animValue

            if (filledWidth > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.20f),
                            accentColor.copy(alpha = 0.65f)
                        )
                    ),
                    size = Size(filledWidth, trackHeight)
                )
            }

            val stepCount = 10
            val stepPx = trackWidth / stepCount
            for (i in 1 until stepCount) {
                val x = i * stepPx
                val isFilled = x <= filledWidth
                drawLine(
                    color = if (isFilled) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f),
                    start = Offset(x, trackHeight * 0.3f),
                    end = Offset(x, trackHeight * 0.7f),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (filledWidth > 0f) {
                drawLine(
                    color = accentColor,
                    start = Offset(filledWidth, 0f),
                    end = Offset(filledWidth, trackHeight),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(10.dp)
                .graphicsLayer {
                    translationX = (widthPx * animValue - 5.dp.toPx()).coerceAtLeast(0f)
                }
                .background(Color.White, RoundedCornerShape(2.dp))
                .border(1.dp, accentColor, RoundedCornerShape(2.dp))
        )
    }
}


@Composable
fun AeroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDanger: Boolean = false,
    accentColor: Color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    appendedContent: @Composable (() -> Unit)? = null,
    lengthLimitCount: Int = Int.MAX_VALUE,
    typeInOnlyInteger: Boolean = false,
    typeInNumberOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (FocusState) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }

    val animBorderColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            isFocused -> accentColor
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(120),
        label = "aeroFieldBorder"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
            isFocused -> accentColor.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(120),
        label = "aeroFieldBg"
    )

    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.6f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (placeholder.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "[ $placeholder ]",
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp
                )

                if (lengthLimitCount < Int.MAX_VALUE) {
                    Text(
                        text = "${value.length}/$lengthLimitCount",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { raw ->
                    val singleLineText = if (raw.contains("\n")) raw.split("\n").last() else raw
                    val isValidInput = when {
                        typeInOnlyInteger -> singleLineText.all { it.isDigit() }
                        typeInNumberOnly -> singleLineText.all { it.isDigit() || it == '.' || it == '-' }
                        else -> true
                    }
                    if (singleLineText.length <= lengthLimitCount && isValidInput) {
                        onValueChange(singleLineText)
                    }
                },
                enabled = enabled,
                singleLine = true,
                cursorBrush = SolidColor(if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        typeInOnlyInteger || typeInNumberOnly -> KeyboardType.Number
                        else -> KeyboardType.Text
                    }
                ),
                textStyle = TextStyle(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .run {
                        if (focusRequester != null) this.focusRequester(focusRequester) else this
                    }
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        onFocusChanged(state)
                    },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .background(animBgColor)
                            .border(1.dp, animBorderColor, shape)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize().padding(start = 6.dp)
                        ) {
                            if (leadingIcon != null) {
                                leadingIcon()
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                innerTextField()
                            }

                            if (trailingIcon != null) {
                                trailingIcon()
                            }
                        }

                        if (isFocused) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .fillMaxHeight(0.5f)
                                    .align(Alignment.CenterStart)
                                    .background(accentColor, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                }
            )

            appendedContent?.invoke()
        }
    }
}

@Composable
fun AeroBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun AeroDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

@Composable
fun AeroDropdownMenu(
    label: String? = null,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    var expanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    val transitionState = remember { MutableTransitionState(false) }

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            transitionState.targetState = true
        } else {
            transitionState.targetState = false
        }
    }

    LaunchedEffect(transitionState.currentState, transitionState.targetState) {
        if (!transitionState.currentState && !transitionState.targetState) {
            showPopup = false
        }
    }

    Box(modifier = modifier) {
        AeroButton(
            text = "${if (label != null) "$label: " else ""}${items.getOrNull(selectedIndex) ?: ""}",
            subText = if (expanded) "▲" else "▼",
            accentColor = accentColor,
            clickSound = Sounds.swipe,
            onClick = { expanded = !expanded }
        )

        if (showPopup) {
            Popup(
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                AnimatedVisibility(
                    visibleState = transitionState,
                    enter = fadeIn(tween(120)) + expandVertically(
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
                        expandFrom = Alignment.Top
                    ) + scaleIn(
                        initialScale = 0.92f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                        animationSpec = tween(120)
                    ),
                    exit = fadeOut(tween(100)) + shrinkVertically(
                        animationSpec = tween(100),
                        shrinkTowards = Alignment.Top
                    ) + scaleOut(
                        targetScale = 0.95f,
                        transformOrigin = TransformOrigin(0.5f, 0f),
                        animationSpec = tween(100)
                    )
                ) {
                    AeroDropdownPanel(
                        items = items,
                        selectedIndex = selectedIndex,
                        accentColor = accentColor,
                        onItemSelected = { index ->
                            onItemSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AeroDropdownPanel(
    items: List<String>,
    selectedIndex: Int,
    accentColor: Color,
    onItemSelected: (Int) -> Unit
) {
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(top = 4.dp)
            .width(IntrinsicSize.Max)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState())
        ) {
            items.forEachIndexed { index, item ->
                AeroDropdownMenuItem(
                    text = item,
                    index = index,
                    isSelected = index == selectedIndex,
                    accentColor = accentColor,
                    onClick = { onItemSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun AeroDropdownMenuItem(
    text: String,
    index: Int,
    isSelected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover2)
    }

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> accentColor.copy(alpha = 0.3f)
            isHovered -> accentColor.copy(alpha = 0.15f)
            isSelected -> accentColor.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(100),
        label = "itemBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(animBgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(Sounds.select)
                    onClick()
                }
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (isHovered || isSelected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(0.6f)
                    .align(Alignment.CenterStart)
                    .background(
                        if (isSelected) accentColor else accentColor.copy(alpha = 0.5f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }

        Row(
            modifier = Modifier.padding(start = if (isHovered || isSelected) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[${index.toString().padStart(2, '0')}] ",
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = text,
                color = when {
                    isPressed || isSelected -> accentColor
                    isHovered -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected || isHovered) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun AeroCyberOverlay(
    progress: Float,
    time: Float,
    modifier: Modifier = Modifier
) {
    if (progress <= 0.01f || progress >= 0.99f) return

    val textMeasurer = rememberTextMeasurer()
    val glitchIntensity = kotlin.math.sin(progress * Math.PI).toFloat()

    val hexChars = remember { "0123456789ABCDEF//:SYS_REBOOT_AERO_LOGIC_FAULT" }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val seed = (time * 100).toInt()
        val rng = Random(seed)

        val blockCount = (15 * glitchIntensity).toInt()
        for (i in 0 until blockCount) {
            val bx = rng.nextFloat() * w
            val by = rng.nextFloat() * h
            val bw = rng.nextFloat() * (w * 0.3f) * glitchIntensity
            val bh = rng.nextFloat() * 12f + 2f

            drawRect(
                color = if (i % 2 == 0) Color(0xFF00E5FF).copy(alpha = 0.6f * glitchIntensity)
                else Color.White.copy(alpha = 0.8f * glitchIntensity),
                topLeft = Offset(bx, by),
                size = Size(bw, bh)
            )
        }

        val textCount = (8 * glitchIntensity).toInt()
        for (i in 0 until textCount) {
            val tx = rng.nextFloat() * (w * 0.8f)
            val ty = rng.nextFloat() * (h * 0.9f)
            val length = rng.nextInt(4, 12)
            val codeStr = (0 until length).map { hexChars[rng.nextInt(hexChars.length)] }.joinToString("")

            drawText(
                textMeasurer = textMeasurer,
                text = codeStr,
                topLeft = Offset(tx, ty),
                style = TextStyle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.7f * glitchIntensity),
                    fontSize = (10 + rng.nextInt(6)).sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        val lineY = (glitchIntensity * h * 1.3f) % h
        drawLine(
            color = Color(0xFF00E5FF).copy(alpha = 0.9f * glitchIntensity),
            start = Offset(0f, lineY),
            end = Offset(w, lineY),
            strokeWidth = 3f
        )
    }
}

@Composable
fun AeroIconButton(
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    isDanger: Boolean = false,
    accentColor: Color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    clickSound: GameSound? = Sounds.select,
    forceHover: Boolean = false,
    animated: Boolean = true,
    shape: Shape = AeroSciFiTheme.ChamferAeroShape(8f)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isHovered || forceHover -> 1.06f
            else -> 1.0f
        },
        animationSpec = if (animated) spring(dampingRatio = 0.6f, stiffness = 800f) else snap(),
        label = "iconBtnScale"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> accentColor.copy(alpha = 0.35f)
            isHovered || forceHover -> accentColor.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        },
        animationSpec = tween(if (animated) 120 else 0),
        label = "iconBtnBg"
    )

    val animBorderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            isHovered || forceHover -> accentColor
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(if (animated) 120 else 0),
        label = "iconBtnBorder"
    )

    val animIconTint by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            isHovered || forceHover -> accentColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(if (animated) 120 else 0),
        label = "iconBtnTint"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(clickSound)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isHovered || isPressed || forceHover) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (isPressed) 0.6f else 0.4f)
                    .align(Alignment.CenterStart)
                    .background(if (isPressed) Color.White else accentColor, RoundedCornerShape(2.dp))
            )
        }

        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = animIconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun AeroSciFiWarningIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    accentColor: Color = MaterialTheme.colorScheme.error
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AeroWarningTransition")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "warningRingRot"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "warningPulse"
    )

    Canvas(modifier = modifier.size(size)) {
        val centerPx = Offset(this.size.width / 2f, this.size.height / 2f)
        val maxRadius = minOf(this.size.width, this.size.height) / 2f - 4.dp.toPx()

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.35f * pulseAlpha),
                    accentColor.copy(alpha = 0.08f * pulseAlpha),
                    Color.Transparent
                ),
                center = centerPx,
                radius = maxRadius
            ),
            radius = maxRadius,
            center = centerPx
        )

        rotate(rotation, pivot = centerPx) {
            drawArc(
                color = accentColor.copy(alpha = 0.7f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(maxRadius * 0.2f, maxRadius * 0.1f)
                    )
                ),
                topLeft = Offset(centerPx.x - maxRadius * 0.88f, centerPx.y - maxRadius * 0.88f),
                size = Size(maxRadius * 1.76f, maxRadius * 1.76f)
            )
        }

        val triRadius = maxRadius * 0.62f
        val p1 = Offset(centerPx.x, centerPx.y - triRadius)
        val p2 = Offset(centerPx.x + triRadius * 0.866f, centerPx.y + triRadius * 0.5f)
        val p3 = Offset(centerPx.x - triRadius * 0.866f, centerPx.y + triRadius * 0.5f)

        val triPath = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            close()
        }

        drawPath(
            path = triPath,
            color = accentColor.copy(alpha = 0.15f * pulseAlpha)
        )

        drawPath(
            path = triPath,
            color = accentColor.copy(alpha = pulseAlpha),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        val markWidth = 3.5.dp.toPx()
        val markTopY = centerPx.y - triRadius * 0.22f
        val markBottomY = centerPx.y + triRadius * 0.12f
        val dotCenterY = centerPx.y + triRadius * 0.28f

        drawLine(
            color = accentColor.copy(alpha = pulseAlpha),
            start = Offset(centerPx.x, markTopY),
            end = Offset(centerPx.x, markBottomY),
            strokeWidth = markWidth,
            cap = StrokeCap.Square
        )

        drawCircle(
            color = accentColor.copy(alpha = pulseAlpha),
            radius = markWidth / 1.1f,
            center = Offset(centerPx.x, dotCenterY)
        )

        val bracketOffset = maxRadius * 0.92f
        val bracketLen = 8.dp.toPx()
        val corners = listOf(
            Offset(-bracketOffset, -bracketOffset),
            Offset(bracketOffset, -bracketOffset),
            Offset(-bracketOffset, bracketOffset),
            Offset(bracketOffset, bracketOffset)
        )

        for (offset in corners) {
            val cornerPos = centerPx + offset
            val dirX = if (offset.x > 0) -1f else 1f
            val dirY = if (offset.y > 0) -1f else 1f

            drawLine(
                color = accentColor.copy(alpha = 0.5f * pulseAlpha),
                start = cornerPos,
                end = cornerPos + Offset(bracketLen * dirX, 0f),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = accentColor.copy(alpha = 0.5f * pulseAlpha),
                start = cornerPos,
                end = cornerPos + Offset(0f, bracketLen * dirY),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
