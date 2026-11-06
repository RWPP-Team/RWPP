/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.Settings
import io.github.rwpp.ui.HudOpacity
import io.github.rwpp.ui.UI
import io.github.rwpp.widget.v2.bounceClick
import org.koin.compose.koinInject

@Composable
fun BorderCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.background.copy(HudOpacity.value),
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = shape,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainer),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = modifier,
            content = content
        )
    } else {
        Card(
            shape = shape,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainer),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = modifier,
            content = content
        )
    }
}

@Composable
fun <T> BasicDropdownMenu(
    expanded: Boolean,
    items: List<T>,
    onItemSelected: (Int, T) -> Unit,
    onDismissRequest: () -> Unit
) = DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest
) {
    items.forEachIndexed { index, t ->
        DropdownMenuItem(
            text = { Text(t.toString()) },
            onClick = {
                onItemSelected(index, t)
                onDismissRequest()
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LargeDropdownMenu(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String,
    items: List<T>,
    hasValue: Boolean = true,
    selectedIndex: Int = -1,
    onItemSelected: (index: Int, item: T) -> Unit,
    selectedItemToString: (T) -> String = { it.toString() },
    selectedItemColor: @Composable (T?, Int) -> Color = { _, _ -> MaterialTheme.colorScheme.onSurface }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            label = { Text(label, fontFamily = MaterialTheme.typography.headlineLarge.fontFamily) },
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                color = selectedItemColor(items.getOrNull(selectedIndex), selectedIndex)
            ),
            colors = RWOutlinedTextColors,
            value = if (hasValue) items.getOrNull(selectedIndex)?.let { selectedItemToString(it) } ?: "" else "",
            enabled = enabled,
            modifier = Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            onValueChange = { },
            shape = RoundedCornerShape(10.dp),
            readOnly = true,
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEachIndexed { index, t ->
                DropdownMenuItem(
                    text = {
                        Text(
                            selectedItemToString(t),
                            style = MaterialTheme.typography.headlineSmall,
                            color = selectedItemColor(t, index)
                        )
                    },
                    onClick = {
                        onItemSelected(index, t)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LargeOutlinedButton(
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    value: String,
    enabled: Boolean = true,
    trailingIcon: @Composable () -> Unit = {},
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .then(modifier)
    ) {
        OutlinedTextField(
            label = label,
            textStyle = MaterialTheme.typography.headlineMedium,
            colors = RWOutlinedTextColors,
            value = value,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = trailingIcon,
            onValueChange = { },
            readOnly = true,
        )

        // Transparent clickable surface on top of OutlinedTextField
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(enabled = enabled) { onClick() },
            color = Color.Transparent,
        ) {}
    }
}

@Composable
fun LargeDividingLine(spacing: @Composable () -> Dp) {
    val color = Color.Black.copy(alpha = .2f)
    val _spacing = spacing()
    Box(
        Modifier
            .padding(top = _spacing, bottom = _spacing)
            .fillMaxWidth()
            .height(3.dp)
            .background(color)
    )
}

@Composable
fun RWSingleOutlinedTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    lengthLimitCount: Int = Int.MAX_VALUE,
    typeInOnlyInteger: Boolean = false,
    typeInNumberOnly: Boolean = false,
    enabled: Boolean = true,
    appendedContent: @Composable (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onFocusChanged: (FocusState) -> Unit = {},
    onValueChange: (String) -> Unit
) {
    Box(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .width(IntrinsicSize.Max)
    ) {
        OutlinedTextField(
            label = {
                Text(
                    label,
                    fontFamily = MaterialTheme.typography.headlineMedium.fontFamily
                )
            },
            textStyle = MaterialTheme.typography.headlineMedium,
            colors = RWOutlinedTextColors,
            value = value,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().composed {
                if (focusRequester != null)
                    focusRequester(focusRequester)
                        .onFocusChanged(onFocusChanged)
                else this
            },
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            onValueChange = {
                onValueChange(
                    (if (it.length <= lengthLimitCount
                        && (!typeInNumberOnly || !typeInOnlyInteger || it.all { s -> s.isDigit() })
                    )
                        it
                    else "").run {
                        if (contains("\n"))
                            split("\n").last()
                        else this
                    }
                )
            },
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = if (typeInNumberOnly) KeyboardType.Number else KeyboardType.Text)
        )

        appendedContent?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RWTextButton(
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(Color.Transparent, RippleAlpha(0f, 0f, 0f, 0f))
    ) {
        Card(
            border = BorderStroke(5.dp, MaterialTheme.colorScheme.surfaceContainer),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(10.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = modifier.bounceClick(onLongClick = onLongClick, onClick = onClick),
        ) {
            Row(
                modifier = Modifier.padding(5.dp),
            ) {
                leadingIcon?.invoke()

                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(5.dp)
                )
            }
        }
    }
}

@Composable
fun RWCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Checkbox(
    checked, onCheckedChange, modifier, enabled, RWCheckBoxColors
)

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    color: Color = MaterialTheme.colorScheme.onSurface,
    drawStroke: Boolean = true,
    modifier: Modifier = Modifier,
    strokeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconLeader: (@Composable () -> Unit)? = null,
) {
    val border = if (drawStroke) Modifier.border(0.5f.dp, strokeColor) else Modifier
    val settings = koinInject<Settings>()
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(weight)
            .then(border)
            .padding(0.dp, 2.dp, 0.dp, 2.dp)
            .then(modifier)
    ) {
        iconLeader?.invoke()

        Text(
            text,
            maxLines = 1,
            color = color,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(0.dp, 2.dp, 0.dp, 2.dp),
            style = if (settings.boldText) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
        )
    }
}

private const val DEFAULT_MINIMUM_TEXT_LINE = 5

@Composable
fun ExpandableText(
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontStyle: FontStyle? = null,
    text: String,
    collapsedMaxLine: Int = DEFAULT_MINIMUM_TEXT_LINE,
    showMoreText: String,
    showMoreStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.W500),
    showLessText: String,
    showLessStyle: SpanStyle = showMoreStyle,
    onExpandToggle: ((Boolean) -> Unit)? = null,
    textAlign: TextAlign? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    var clickable by remember { mutableStateOf(false) }
    var lastCharIndex by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .clickable(clickable) {
                val expanded = !isExpanded
                isExpanded = expanded
                onExpandToggle?.invoke(expanded)
            }
            .then(modifier)
    ) {
        Text(
            modifier = textModifier
                .fillMaxWidth()
                .animateContentSize(),
            text = buildAnnotatedString {
                if (clickable) {
                    if (isExpanded) {
                        append(text)
                        withStyle(style = showLessStyle) { append(showLessText) }
                    } else {
                        val adjustText = text.substring(startIndex = 0, endIndex = lastCharIndex)
                            .dropLast(showMoreText.length)
                            .dropLastWhile { Character.isWhitespace(it) || it == '.' }
                        append(adjustText)
                        withStyle(style = showMoreStyle) { append(showMoreText) }
                    }
                } else {
                    append(text)
                }
            },
            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLine,
            fontStyle = fontStyle,
            onTextLayout = { textLayoutResult ->
                if (!isExpanded && textLayoutResult.hasVisualOverflow) {
                    clickable = true
                    lastCharIndex = textLayoutResult.getLineEnd(collapsedMaxLine - 1)
                }
            },
            style = style,
            textAlign = textAlign
        )
    }
}

//@Composable
//fun Modifier.simpleVerticalScrollbar(
//    state: LazyListState,
//    width: Dp = 8.dp
//): Modifier {
//    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
//    val duration = if (state.isScrollInProgress) 150 else 500
//
//    val alpha by animateFloatAsState(
//        targetValue = targetAlpha,
//        animationSpec = tween(durationMillis = duration)
//    )
//
//    return drawWithContent {
//        drawContent()
//
//        val firstVisibleElementIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
//        val needDrawScrollbar = state.isScrollInProgress || alpha > 0.0f
//
//        // Draw scrollbar if scrolling or if the animation is still running and lazy column has content
//        if (needDrawScrollbar && firstVisibleElementIndex != null) {
//            val elementHeight = this.size.height / state.layoutInfo.totalItemsCount
//            val scrollbarOffsetY = firstVisibleElementIndex * elementHeight + state.firstVisibleItemScrollOffset / 4
//            val scrollbarHeight = elementHeight * 4
//
//            drawRect(
//                color = Color(112, 130, 56),
//                topLeft = Offset(this.size.width - width.toPx(), scrollbarOffsetY),
//                size = Size(width.toPx(), scrollbarHeight),
//                alpha = alpha,
//            )
//        }
//    }
//}
//
