/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import kotlin.ranges.coerceAtLeast

/**
 * Room layout switches between a naturally scrolling compact page and the original bounded
 * desktop workspace. In compact mode the map defines the upper-row height; the player list is
 * measured to that height and can scroll independently inside it.
 */
@Composable
internal fun RoomWorkspaceLayout(
    modifier: Modifier = Modifier,
    compact: Boolean,
    header: @Composable () -> Unit = {},
    map: @Composable () -> Unit,
    players: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    pageScrollState: ScrollState = rememberScrollState(),
) {
    if (compact) {
        Column(
            modifier.fillMaxWidth().verticalScroll(pageScrollState).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            CompactRoomUpperRow(map, players)
            chat()
        }
    } else {
        Column(
            modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            Row(
                Modifier.fillMaxWidth().weight(2f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(0.46f).fillMaxHeight()) { map() }
                Box(Modifier.weight(0.54f).fillMaxHeight()) { players() }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { chat() }
        }
    }
}

@Composable
private fun CompactRoomUpperRow(
    map: @Composable () -> Unit,
    players: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { map() }
            Box { players() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        require(measurables.size == 2) { "Compact room row expects map and players" }
        val width = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val available = (width - gap).coerceAtLeast(0)
        val mapWidth = (available * 0.40f).toInt().coerceAtLeast(0)
        val playersWidth = available - mapWidth
        val mapPlaceable = measurables[0].measure(
            Constraints(
                minWidth = mapWidth,
                maxWidth = mapWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
        )
        val playersPlaceable = measurables[1].measure(
            Constraints(
                minWidth = playersWidth,
                maxWidth = playersWidth,
                minHeight = mapPlaceable.height,
                maxHeight = mapPlaceable.height,
            )
        )
        val measuredWidth = constraints.constrainWidth(width)
        val measuredHeight = constraints.constrainHeight(mapPlaceable.height)
        layout(measuredWidth, measuredHeight) {
            mapPlaceable.placeRelative(0, 0)
            playersPlaceable.placeRelative(mapWidth + gap, 0)
        }
    }
}

/** The map pane used by BattleRoom and its small-window render regression. */
@Composable
internal fun RoomMapPane(
    mapPreview: @Composable () -> Unit,
    mapType: String,
    mapName: String,
    details: String,
    isHost: Boolean,
    onSelectMap: () -> Unit,
    onOptions: () -> Unit,
    onStart: () -> Unit,
    sectorInfoLabel: String,
    hostAccessLabel: String,
    guestLabel: String,
    startLabel: String,
    compact: Boolean,
    selectMapLabel: String,
    optionsLabel: String,
    actionsModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        AeroPanel(modifier.fillMaxWidth().wrapContentHeight()) {
            Column(
                Modifier.fillMaxWidth().wrapContentHeight().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(170.dp).clipToBounds()) { mapPreview() }
                Text(
                    mapType,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    mapName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    details,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
                if (isHost) {
                    RoomMapActionBar(
                        compact = true,
                        startLabel = startLabel,
                        selectMapLabel = selectMapLabel,
                        optionsLabel = optionsLabel,
                        modifier = actionsModifier,
                        onSelectMap = onSelectMap,
                        onOptions = onOptions,
                        onStart = onStart,
                    )
                }
            }
        }
    } else {
        AeroPanel(modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AeroHeader(
                        title = sectorInfoLabel,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        if (isHost) hostAccessLabel else guestLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val previewHeight = minOf(170.dp, maxHeight * 0.43f)
                        .coerceAtLeast(minOf(64.dp, maxHeight))
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(previewHeight).clipToBounds()) { mapPreview() }
                        Column(
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                                .padding(top = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                mapType, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                mapName, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                details, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp
                            )
                        }
                    }
                }
                if (isHost) {
                    RoomMapActionBar(
                        compact = false,
                        startLabel = startLabel,
                        selectMapLabel = selectMapLabel,
                        optionsLabel = optionsLabel,
                        modifier = actionsModifier,
                        onSelectMap = onSelectMap,
                        onOptions = onOptions,
                        onStart = onStart,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoomMapActionBar(
    compact: Boolean,
    startLabel: String,
    selectMapLabel: String,
    optionsLabel: String,
    onSelectMap: () -> Unit,
    onOptions: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackActions = compact && maxWidth < 280.dp
            val mapText = selectMapLabel + if (compact) "" else " (M)"
            val optionsText = optionsLabel + if (compact) "" else " (O)"
            if (stackActions) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AeroButton(mapText, onSelectMap, Modifier.fillMaxWidth(), clickSound = Sounds.openPopup)
                    AeroButton(optionsText, onOptions, Modifier.fillMaxWidth(), clickSound = Sounds.openPopup)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                ) {
                    AeroButton(mapText, onSelectMap, Modifier.weight(1f), clickSound = Sounds.openPopup)
                    AeroButton(optionsText, onOptions, Modifier.weight(1f), clickSound = Sounds.openPopup)
                }
            }
        }
        AeroButton(
            text = startLabel,
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            accentColor = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** Real compactable chat panel. Compact history grows with its messages and scrolls with the page. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RoomChatPanel(
    history: AnnotatedString,
    message: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    chatFocusRequester: FocusRequester,
    isHost: Boolean,
    isSandbox: Boolean,
    isLocked: Boolean,
    onChangeSite: () -> Unit,
    onAddAI: (Int) -> Unit,
    onToggleLock: () -> Unit,
    chatTitle: String,
    chatTag: String,
    changeSiteDescription: String,
    teamLockDescription: String,
    sendMessageDescription: String,
    addAiLabel: String,
    compact: Boolean,
    onComposerFocusChanged: (Boolean) -> Unit = {},
    historyScrollState: ScrollState = rememberScrollState(),
    toolbarModifier: Modifier = Modifier,
    historyModifier: Modifier = Modifier,
    composerModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val panelModifier = if (compact) modifier.fillMaxWidth().wrapContentHeight() else modifier.fillMaxSize()
    AeroPanel(panelModifier) {
        Column(
            Modifier
                .then(if (compact) Modifier.fillMaxWidth().wrapContentHeight() else Modifier.fillMaxSize())
                .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp)
        ) {
            // The composer has one stable composition slot on both layouts. Incoming room
            // refreshes and IME resizing must not recreate the focused text field.
            Row(
                toolbarModifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoomChatComposer(
                    message, onMessageChange, onSend, chatFocusRequester,
                    sendMessageDescription, compact, composerModifier.weight(1f), onComposerFocusChanged
                )
                if (compact) AeroIconButton(
                    rememberVectorPainter(Icons.Default.Person), onClick = onChangeSite,
                    contentDescription = changeSiteDescription, size = 48.dp, iconSize = 20.dp,
                    clickSound = Sounds.openPopup,
                ) else AeroButton(
                    changeSiteDescription + " (C)", onClick = onChangeSite,
                    modifier = Modifier.width(130.dp).height(48.dp), clickSound = Sounds.openPopup
                )
                if (isHost) RoomAiActionButton(
                    size = 48.dp, compact = true, label = addAiLabel,
                    onClick = { onAddAI(1) }, onLongClick = { onAddAI(10) },
                )
                if (!isSandbox && isHost) AeroIconButton(
                    rememberVectorPainter(Icons.Rounded.Lock), onClick = onToggleLock,
                    contentDescription = teamLockDescription, size = 48.dp, iconSize = 20.dp,
                    isDanger = isLocked, forceHover = isLocked,
                )
            }
            AeroDivider(Modifier.padding(vertical = 4.dp))
            if (!compact) LaunchedEffect(history) {
                withFrameNanos { }
                historyScrollState.scrollTo(0)
            }
            val historyViewport = if (compact) Modifier else Modifier.weight(1f).verticalScroll(historyScrollState)
            SelectionContainer(Modifier.fillMaxWidth().then(historyModifier).then(historyViewport)) {
                Text(
                    history, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun RoomAiActionButton(
    size: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val shape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            isHovered -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 750f),
        label = "addAiScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> accent.copy(alpha = 0.35f)
            isHovered -> accent.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        },
        animationSpec = tween(120),
        label = "addAiBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            isHovered -> accent
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(120),
        label = "addAiBorder",
    )

    Box(
        modifier = modifier
            .then(if (compact) Modifier.size(size) else Modifier.height(size))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(backgroundColor)
            .border(if (isHovered || isPressed) 1.5.dp else 1.dp, borderColor, shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    Sounds.playSound(Sounds.select)
                    onClick()
                },
                onLongClick = {
                    Sounds.playSound(Sounds.openPopup)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = label,
                tint = if (isHovered || isPressed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp),
            )
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                Text(
                    label,
                    color = if (isHovered || isPressed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomChatComposer(
    message: String,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    chatFocusRequester: FocusRequester,
    placeholder: String,
    compact: Boolean,
    modifier: Modifier,
    onFocusChanged: (Boolean) -> Unit,
) {
    Row(
        modifier.heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "> ", style = TextStyle(
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        )
        BasicTextField(
            value = message,
            onValueChange = onMessageChange,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (message.isNotBlank()) onSend() }),
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).focusRequester(chatFocusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }.onKeyEvent {
                    if (it.type == KeyEventType.KeyDown &&
                        (it.key == Key.Enter || it.key == Key.NumPadEnter) && message.isNotEmpty()
                    ) {
                        onSend(); true
                    } else false
                },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (message.isEmpty()) Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    innerTextField()
                }
            },
        )
        AeroIconButton(
            rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward), onClick = onSend,
            contentDescription = placeholder, size = 48.dp,
            iconSize = 22.dp
        )
    }
}
