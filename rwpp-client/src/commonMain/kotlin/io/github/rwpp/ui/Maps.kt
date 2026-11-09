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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.ResponsiveFilterChoices
import io.github.rwpp.widget.ResponsiveSelectionBody
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.usesCompactSelectionLayout
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyVerticalGridScrollbar

@Composable
fun MapViewDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    lastSelectedIndex: Int = 0,
    lastSelectedMapType: GameSetupMapType = GameSetupMapType.skirmishMap,
    displayMaps: List<GameMap>,
    isLoading: Boolean = false,
    loadError: String? = null,
    mapTypeItems: List<GameSetupMapType>,
    lastSelectedMapKey: String? = null,
    onRefresh: () -> Unit,
    onSelectedMap: (Int, GameMap) -> Unit
) {
    var lastConfirmedMapKey by remember { mutableStateOf(lastSelectedMapKey) }
    LaunchedEffect(lastSelectedMapKey) {
        if (lastSelectedMapKey != null) lastConfirmedMapKey = lastSelectedMapKey
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest
    ) { dismissAction ->
        val dialogShape = remember { AeroSciFiTheme.ChamferAeroShape(16f) }

        Surface(
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .padding(12.dp)
                .autoClearFocus()
        ) {

            var filter by remember { mutableStateOf("") }
            var selectedIndex0 by remember { mutableIntStateOf(lastSelectedMapType.ordinal) }
            val mapType = remember(selectedIndex0, mapTypeItems) {
                mapTypeItems[selectedIndex0.coerceAtMost(mapTypeItems.lastIndex)]
            }
            val maps by remember(displayMaps, mapType, filter) {
                derivedStateOf {
                    displayMaps.filter { it.displayName().contains(filter, true) && it.mapType == mapType }
                }
            }
            var selectedMapKey by remember {
                mutableStateOf(lastConfirmedMapKey ?: maps.getOrNull(lastSelectedIndex)?.mapSelectionKey())
            }
            val selectedMapIndex = resolveMapSelectionIndex(maps, selectedMapKey, lastSelectedIndex)
            val compactListState = rememberLazyListState()
            var revealSelectedMapIndex by remember { mutableIntStateOf(-1) }
            LaunchedEffect(revealSelectedMapIndex) {
                val index = revealSelectedMapIndex
                if (index >= 0) {
                    compactListState.animateScrollToItem(COMPACT_MAP_PAGE_HEADER_ITEMS + index)
                    revealSelectedMapIndex = -1
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Use the host's resolved tier so Middle/Large keep the desktop grid and preview panes.
                val compact = usesCompactSelectionLayout(LocalWindowManager.current)
                if (compact) {
                    ResponsiveSelectionBody(
                        compact = true,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        compactState = compactListState,
                        compactContent = {
                            item(key = "map-dialog-header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AeroHeader(
                                        title = "MAP VIEW".uppercase(),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    AeroIconButton(
                                        painter = rememberVectorPainter(Icons.Default.Close),
                                        size = 36.dp,
                                        iconSize = 18.dp,
                                        isDanger = true,
                                        clickSound = Sounds.closePopup,
                                        onClick = dismissAction,
                                    )
                                }
                                Text(
                                    text = readI18n("rwpp.multiplayer.room.mapsCount", maps.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            item(key = "map-dialog-search") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AeroTextField(
                                        value = filter,
                                        onValueChange = { filter = it },
                                        placeholder = readI18n("rwpp.multiplayer.room.searchMaps"),
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    AeroIconButton(
                                        painter = rememberVectorPainter(Icons.Default.Refresh),
                                        size = 42.dp,
                                        iconSize = 20.dp,
                                        onClick = onRefresh,
                                    )
                                }
                            }
                            item(key = "map-dialog-type-filter") {
                                ResponsiveFilterChoices(
                                    label = readI18n("rwpp.multiplayer.room.mapType"),
                                    items = mapTypeItems.map { it.displayName },
                                    selectedIndex = selectedIndex0.coerceAtMost(mapTypeItems.lastIndex),
                                    onItemSelected = { selectedIndex0 = it },
                                )
                            }
                            item(key = "map-dialog-list-heading") {
                                AeroDivider()
                                AeroHeader(title = "MAPS FOUND: ${maps.size}")
                            }
                            if (maps.isEmpty()) {
                                item(key = "map-dialog-empty") {
                                    Text(
                                        text = when {
                                            isLoading -> readI18n("rwpp.catalog.loading")
                                            loadError != null -> readI18n("rwpp.catalog.failed", loadError)
                                            else -> readI18n("rwpp.multiplayer.room.noMapsMatchFilter")
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            } else {
                                items(count = maps.size, key = { maps[it].mapSelectionKey() }) { index ->
                                    val map = maps[index]
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        MapSelectionButton(
                                            text = map.displayName(),
                                            selected = index == selectedMapIndex,
                                            onClick = {
                                                selectedMapKey = map.mapSelectionKey()
                                                revealSelectedMapIndex = index
                                            },
                                        )
                                        if (index == selectedMapIndex) {
                                            AeroPanel(modifier = Modifier.fillMaxWidth()) {
                                                MapDetailSidebar(
                                                    map = map,
                                                    mapType = mapType,
                                                    showImage = mapType != GameSetupMapType.savedGame,
                                                    compact = true,
                                                    fitContent = true,
                                                    onConfirmSelect = {
                                                        lastConfirmedMapKey = map.mapSelectionKey()
                                                        onSelectedMap(index, map)
                                                        dismissAction()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        desktopContent = {},
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AeroHeader(
                                title = "MAP VIEW".uppercase(),
                                tag = "${maps.size} MAPS LOADED",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(if (compact) 6.dp else 12.dp))
                            AeroIconButton(
                                painter = rememberVectorPainter(Icons.Default.Close),
                                size = 32.dp,
                                iconSize = 16.dp,
                                isDanger = true,
                                clickSound = Sounds.closePopup,
                                onClick = dismissAction
                            )
                        }

                        Spacer(modifier = Modifier.height(if (compact) 4.dp else 12.dp))

                        if (compact) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    placeholder = readI18n("rwpp.multiplayer.room.searchMaps"),
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                AeroIconButton(
                                    painter = rememberVectorPainter(Icons.Default.Refresh),
                                    size = 40.dp,
                                    iconSize = 18.dp,
                                    onClick = onRefresh,
                                )
                            }
                        } else {
                            // Desktop keeps the complete filter toolbar and side-by-side map preview.
                            AeroPanel(
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.weight(1.2f)) {
                                        AeroDropdownMenu(
                                            label = readI18n("rwpp.multiplayer.room.mapType"),
                                            items = mapTypeItems.map { it.displayName },
                                            selectedIndex = selectedIndex0.coerceAtMost(mapTypeItems.lastIndex),
                                            onItemSelected = { index -> selectedIndex0 = index },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    AeroTextField(
                                        value = filter,
                                        onValueChange = { filter = it },
                                        placeholder = "",
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1.5f)
                                            .align(Alignment.CenterVertically)
                                    )
                                    AeroIconButton(
                                        painter = rememberVectorPainter(Icons.Default.Refresh),
                                        size = 48.dp,
                                        iconSize = 20.dp,
                                        onClick = onRefresh
                                    )
                                }
                            }
                        }

                        if (!compact) Spacer(modifier = Modifier.height(12.dp))

                        val gridState = rememberLazyGridState()
                        LaunchedEffect(Unit) {
                            if (selectedMapIndex in maps.indices) gridState.scrollToItem(selectedMapIndex)
                        }

                        val listPane: @Composable () -> Unit = {
                            AeroPanel(
                                modifier = Modifier.fillMaxSize(),
                                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ) {
                                if (maps.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (isLoading) {
                                                LinearProgressIndicator(Modifier.width(120.dp))
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(40.dp)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                            Text(
                                                text = when {
                                                    isLoading -> readI18n("rwpp.catalog.loading")
                                                    loadError != null -> readI18n("rwpp.catalog.failed", loadError)
                                                    else -> readI18n("rwpp.multiplayer.room.noMapsMatchFilter")
                                                },
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                } else {
                                    LazyVerticalGridScrollbar(gridState) {
                                        LazyVerticalGrid(
                                            state = gridState,
                                            columns = GridCells.Adaptive(minSize = if (compact) 120.dp else 130.dp),
                                            contentPadding = PaddingValues(if (compact) 6.dp else 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(count = maps.size, key = { maps[it].mapSelectionKey() }) { index ->
                                                val map = maps[index]
                                                val isSelected = index == selectedMapIndex
                                                val name = rememberSaveable(map) { map.displayName() }
                                                MapGridCard(
                                                    name = name,
                                                    model = map,
                                                    isSelected = isSelected,
                                                    showImage = mapType != GameSetupMapType.savedGame,
                                                    compact = compact,
                                                    onClick = { selectedMapKey = map.mapSelectionKey() },
                                                    onDoubleClick = {
                                                        lastConfirmedMapKey = map.mapSelectionKey()
                                                        onSelectedMap(index, map)
                                                        dismissAction()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val detailsPane: @Composable () -> Unit = {
                            AeroPanel(
                                modifier = Modifier.fillMaxSize(),
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            ) {
                                val currentMap = maps.getOrNull(selectedMapIndex)
                                MapDetailSidebar(
                                    map = currentMap,
                                    mapType = mapType,
                                    showImage = mapType != GameSetupMapType.savedGame,
                                    compact = compact,
                                    onConfirmSelect = {
                                        if (currentMap != null) {
                                            lastConfirmedMapKey = currentMap.mapSelectionKey()
                                            onSelectedMap(selectedMapIndex, currentMap)
                                            dismissAction()
                                        }
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.weight(1.8f).fillMaxHeight()) { listPane() }
                            Box(Modifier.weight(1f).fillMaxHeight()) { detailsPane() }
                        }
                    }
                }
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

internal const val COMPACT_MAP_PAGE_HEADER_ITEMS = 4

/** Stable across refreshes and filter changes, unlike an index in the currently visible list. */
internal fun GameMap.mapSelectionKey(): String = listOf(
    mapType.name,
    enginePath.lowercase(),
    id.toString(),
    mapName.lowercase(),
).joinToString(separator = "") { "${it.length}:$it" }

internal fun resolveMapSelectionIndex(
    maps: List<GameMap>,
    selectedMapKey: String?,
    lastSelectedIndex: Int,
): Int {
    if (maps.isEmpty()) return -1
    val selectedIndex = maps.indexOfFirst { it.mapSelectionKey() == selectedMapKey }
    return if (selectedIndex >= 0) selectedIndex else lastSelectedIndex.takeIf { it in maps.indices } ?: 0
}

@Composable
internal fun MapSelectionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            isPressed -> 0.96f
            isHovered -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 750f),
        label = "mapSelectionScale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> accent.copy(alpha = 0.34f)
            selected -> accent.copy(alpha = 0.2f)
            isHovered -> accent.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        },
        animationSpec = tween(120),
        label = "mapSelectionBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White
            selected -> accent
            isHovered -> accent.copy(alpha = 0.8f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(120),
        label = "mapSelectionBorder",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(backgroundColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
            ) {
                Sounds.playSound(Sounds.select)
                onClick()
            }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (selected || isHovered || isPressed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = if (selected || isHovered) FontWeight.Bold else FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected || isHovered || isPressed) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .background(if (isPressed) Color.White else accent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
fun LazyGridItemScope.MapGridCard(
    name: String,
    model: Any?,
    isSelected: Boolean,
    showImage: Boolean = true,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isHovered -> 1.03f
            else -> 1.0f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = "cardScale"
    )

    val activeColor = MaterialTheme.colorScheme.primary

    val animBorderColor by animateColorAsState(
        targetValue = when {
            isSelected -> activeColor
            isPressed -> Color.White
            isHovered -> activeColor.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(120),
        label = "cardBorder"
    )

    val animBgColor by animateColorAsState(
        targetValue = when {
            isSelected -> activeColor.copy(alpha = 0.2f)
            isPressed -> activeColor.copy(alpha = 0.25f)
            isHovered -> activeColor.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        },
        animationSpec = tween(120),
        label = "cardBg"
    )

    val itemShape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }

    Box(
        modifier = Modifier
            .animateItem()
            .height(if (compact) 72.dp else 130.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(itemShape)
            .background(animBgColor)
            .border(if (isSelected) 1.5.dp else 1.dp, animBorderColor, itemShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (isSelected) {
                        Sounds.playSound(Sounds.closePopup)
                        onDoubleClick()
                    } else {
                        Sounds.playSound(Sounds.select)
                        onClick()
                    }
                }
            )
            .padding(if (compact) 4.dp else 6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (showImage) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(AeroSciFiTheme.ChamferAeroShape(4f))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            AeroSciFiTheme.ChamferAeroShape(4f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (model != null && model !is NetworkMap) {
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(2.dp)
                                .fillMaxSize()
                        )
                    } else {
                        Text(
                            text = readI18n("rwpp.common.none"),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Text(
                text = name,
                modifier = Modifier.padding(top = 4.dp),
                color = if (isSelected || isHovered || isPressed) activeColor else MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected || isHovered) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 选中/悬停科技感指示边条
        if (isSelected || isHovered) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .align(Alignment.TopStart)
                    .background(
                        if (isSelected) activeColor else activeColor.copy(alpha = 0.6f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

/**
 * 右侧地图大图预览与操作面板
 */
@Composable
private fun MapDetailSidebar(
    map: GameMap?,
    mapType: GameSetupMapType,
    showImage: Boolean,
    compact: Boolean,
    fitContent: Boolean = false,
    onConfirmSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .then(if (fitContent) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .padding(if (compact) 10.dp else 14.dp)
            .then(if (compact && !fitContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = if (compact) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[ ${readI18n("rwpp.multiplayer.room.mapPreview").uppercase()} ]",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                AeroBadge(
                    text = mapType.displayName.uppercase(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            AeroMapPreview(map, showImage)

            Spacer(modifier = Modifier.height(12.dp))

            if (map != null) {
                Text(
                    text = map.displayName(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                AeroDivider()

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MapInfoRow(
                        label = readI18n("rwpp.common.filename"),
                        value = map.mapName.ifEmpty { readI18n("rwpp.common.notAvailable") })
                    MapInfoRow(label = readI18n("rwpp.common.mapId"), value = map.id.toString())
                    MapInfoRow(
                        label = readI18n("rwpp.common.status"),
                        value = readI18n("rwpp.multiplayer.room.mapReady")
                    )
                }
            }
        }

        // 底部确认按钮
        AeroButton(
            text = readI18n("rwpp.multiplayer.room.mapView").uppercase(),
            subText = "${readI18n("rwpp.common.confirm").uppercase()} ▶",
            onClick = onConfirmSelect,
            clickSound = Sounds.closePopup,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MapInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
