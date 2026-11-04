/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.sound.Sounds

/**
 * Compact selection pages use one virtualized vertical page scroll. Headers, filters, selectable
 * entries, and details are all list items in that same scroll container. Larger windows keep
 * their page-specific desktop layout unchanged.
 */
@Composable
fun ResponsiveSelectionBody(
    compact: Boolean,
    modifier: Modifier = Modifier,
    compactState: LazyListState? = null,
    compactContent: LazyListScope.() -> Unit,
    desktopContent: @Composable () -> Unit,
) {
    if (compact) {
        LazyColumn(
            modifier = modifier,
            state = compactState ?: rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = compactContent,
        )
    } else {
        Box(modifier = modifier) {
            desktopContent()
        }
    }
}

/** Middle windows retain the desktop toolbars and panes; only Small uses the compact page scroll. */
fun usesCompactSelectionLayout(windowManager: WindowManager): Boolean = windowManager == WindowManager.Small

/** Compact inline choices keep filters available without opening anchored popups. */
@Composable
fun ResponsiveFilterChoices(
    label: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.widthIn(min = 52.dp, max = 84.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                ResponsiveFilterChoice(
                    text = item,
                    selected = selected,
                    onClick = { onItemSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun ResponsiveFilterChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    androidx.compose.runtime.LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else if (isHovered) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 700f),
        label = "filterChoiceScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> accent.copy(alpha = 0.32f)
            selected -> accent.copy(alpha = 0.18f)
            isHovered -> accent.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "filterChoiceBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            selected || isHovered -> accent
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        },
        animationSpec = tween(120),
        label = "filterChoiceBorder",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
            ) {
                Sounds.playSound(Sounds.select)
                onClick()
            }
            .semantics {
                this.selected = selected
            }
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected || isHovered || isPressed) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(if (isPressed) Color.White else accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = text,
                color = if (selected || isHovered || isPressed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
