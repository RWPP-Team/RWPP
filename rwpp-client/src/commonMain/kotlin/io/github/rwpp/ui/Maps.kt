/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.api.coroutines.GameScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.rwpp.api.game.map.GameMap
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyVerticalGridScrollbar

@Composable
fun MapViewDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    lastSelectedIndex: Int = 0,
    lastSelectedMapType: GameSetupMapType = GameSetupMapType.skirmishMap,
    displayMaps: List<GameMap>,
    mapTypeItems: List<GameSetupMapType>,
    onRefresh: () -> Unit,
    onSelectedMap: (Int, GameMap) -> Unit
) = AnimatedAlertDialog(
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

        var maps by remember { mutableStateOf(displayMaps) }
        var filter by remember { mutableStateOf("") }
        var selectedIndex0 by remember { mutableIntStateOf(lastSelectedMapType.ordinal) }
        val mapType = remember(selectedIndex0, mapTypeItems) {
            mapTypeItems[selectedIndex0.coerceAtMost(mapTypeItems.lastIndex)]
        }

        var selectedMapIndex by remember { mutableIntStateOf(lastSelectedIndex) }


        LaunchedEffect(mapType, filter, displayMaps) {
            maps = displayMaps.filter {
                map -> map.displayName().contains(filter, true) && map.mapType == mapType
            }
            if (selectedMapIndex !in maps.indices) {
                selectedMapIndex = 0
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroHeader(
                        title = readI18n("rwpp.multiplayer.room.mapView").uppercase(),
                        tag = "${maps.size} MAPS LOADED",
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Close),
                        size = 32.dp,
                        iconSize = 16.dp,
                        isDanger = true,
                        clickSound = Sounds.closePopup,
                        onClick = dismissAction
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. 规整对齐的控制工具栏 (AeroPanel)
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
                        // 地图类型下拉菜单
                        Box(modifier = Modifier.weight(1.2f)) {
                            AeroDropdownMenu(
                                label = readI18n("rwpp.multiplayer.room.mapType"),
                                items = mapTypeItems.map { it.getDisplayName() },
                                selectedIndex = selectedIndex0.coerceAtMost(mapTypeItems.lastIndex),
                                onItemSelected = { index -> selectedIndex0 = index },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        AeroTextField(
                            value = filter,
                            onValueChange = {
                                filter = it
                            },
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
                            onClick = {
                               onRefresh()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. 主体区域：地图网格 (左) + 实时预览详情面板 (右)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 左侧：地图选择网格面板
                    AeroPanel(
                        modifier = Modifier
                            .weight(1.8f)
                            .fillMaxHeight(),
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    ) {
                        if (maps.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "NO MAPS MATCH FILTER",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        } else {
                            val gridState = rememberLazyGridState()

                            LaunchedEffect(Unit) {
                                if (lastSelectedIndex in maps.indices) {
                                    gridState.scrollToItem(lastSelectedIndex)
                                }
                            }


                            LazyVerticalGridScrollbar(
                                gridState
                            ) {
                                LazyVerticalGrid(
                                    state = gridState,
                                    columns = GridCells.Adaptive(minSize = 130.dp),
                                    contentPadding = PaddingValues(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        count = maps.size,
                                        key = { maps[it].mapName + maps[it].id }
                                    ) { index ->
                                        val map = maps[index]
                                        val isSelected = index == selectedMapIndex
                                        val name = rememberSaveable(map) { map.displayName() }

                                        MapGridCard(
                                            name = name,
                                            model = map,
                                            isSelected = isSelected,
                                            showImage = mapType != GameSetupMapType.savedGame,
                                            onClick = { selectedMapIndex = index },
                                            onDoubleClick = {
                                                onSelectedMap(index, map)
                                                dismissAction()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 右侧：新增地图详情与实时预览面板
                    AeroPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    ) {
                        val currentMap = maps.getOrNull(selectedMapIndex)

                        MapDetailSidebar(
                            map = currentMap,
                            mapType = mapType,
                            showImage = mapType != GameSetupMapType.savedGame,
                            onConfirmSelect = {
                                if (currentMap != null) {
                                    onSelectedMap(selectedMapIndex, currentMap)
                                    dismissAction()
                                }
                            }
                        )
                    }
                }
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
    onDoubleClick: () -> Unit
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
            .height(130.dp)
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
                        Sounds.closePopup?.play(GameScope.coroutineContext)
                        onDoubleClick()
                    } else {
                        Sounds.select?.play(GameScope.coroutineContext)
                        onClick()
                    }
                }
            )
            .padding(6.dp)
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
                            text = "NONE",
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
    onConfirmSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
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
                    text = "[ MAP PREVIEW ]",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                AeroBadge(
                    text = mapType.getDisplayName().uppercase(),
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
                    MapInfoRow(label = "FILENAME", value = map.mapName.ifEmpty { "N/A" })
                    MapInfoRow(label = "MAP ID", value = map.id.toString())
                    MapInfoRow(label = "STATUS", value = "READY FOR MATCH")
                }
            }
        }

        // 底部确认按钮
        AeroButton(
            text = readI18n("rwpp.multiplayer.room.mapView").uppercase(),
            subText = "CONFIRM ▶",
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
