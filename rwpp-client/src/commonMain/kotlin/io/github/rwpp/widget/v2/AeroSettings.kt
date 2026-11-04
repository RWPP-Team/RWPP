/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.sound.Sounds

@Composable
fun AeroTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(6f) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), shape)
            .padding(4.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tabWidth = maxOf(80.dp, (maxWidth - 8.dp) / tabs.size.coerceAtLeast(1))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    AeroTab(
                        selected = selectedTabIndex == index,
                        onClick = { onTabSelected(index) },
                        text = title,
                        accentColor = accentColor,
                        modifier = Modifier.width(tabWidth)
                    )
                }
            }
        }
    }
}

@Composable
fun AeroTab(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover2)
    }

    val animBgColor by animateColorAsState(
        targetValue = when {
            isPressed -> accentColor.copy(alpha = 0.35f)
            selected -> accentColor.copy(alpha = 0.20f)
            isHovered -> accentColor.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "tabBg"
    )

    val animBorderColor by animateColorAsState(
        targetValue = when {
            selected -> accentColor
            isHovered -> accentColor.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "tabBorder"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            selected -> Color.White
            isHovered -> accentColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(120),
        label = "tabText"
    )

    val shape = remember { AeroSciFiTheme.ChamferAeroShape(4f) }

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(Sounds.select)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f)
                    .height(2.dp)
                    .background(accentColor)
            )
        }

        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun AeroSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subLabel != null) {
                Text(
                    text = subLabel,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Box(
            modifier = Modifier.width(220.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            control()
        }
    }
}

@Composable
fun AeroSettingGroup(
    title: String,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AeroPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AeroHeader(
                title = title,
                tag = tag ?: "SECTION",
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun AeroSettingToggle(
    label: String,
    defaultValue: Boolean,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    var checked by remember(label, defaultValue) { mutableStateOf(defaultValue) }
    AeroSettingRow(
        label = label,
        subLabel = subLabel,
        modifier = modifier
    ) {
        AeroToggleTile(
            "",
            checked = checked,
            onCheckedChange = {
                checked = it
                onCheckedChange(it)
            },
        )
    }
}

@Composable
fun AeroSettingSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueFormat: (Float) -> String = { "%.2f".format(it) }
) {
    AeroSettingRow(
        label = label,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AeroSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f)
            )
            AeroBadge(
                text = valueFormat(value),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AeroSettingDropDown(
    label: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    AeroSettingRow(
        label = label,
        modifier = modifier
    ) {
        AeroDropdownMenu(
            label = null,
            items = items,
            selectedIndex = selectedIndex,
            onItemSelected = { idx -> onItemSelected(idx, items[idx]) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AeroSettingTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    typeInNumberOnly: Boolean = false,
    typeInOnlyInteger: Boolean = false,
    lengthLimitCount: Int = Int.MAX_VALUE,
    trailingIcon: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit
) {
    AeroSettingRow(
        label = label,
        modifier = modifier
    ) {
        AeroTextField(
            value = value,
            placeholder = "",
            onValueChange = { input ->
                if (input.length <= lengthLimitCount) {
                    if (typeInNumberOnly) {
                        if (typeInOnlyInteger) {
                            if (input.isEmpty() || input.all { it.isDigit() || it == '-' }) {
                                onValueChange(input)
                            }
                        } else {
                            if (input.isEmpty() || input.toFloatOrNull() != null || input == "-") {
                                onValueChange(input)
                            }
                        }
                    } else {
                        onValueChange(input)
                    }
                }
            },
            trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
