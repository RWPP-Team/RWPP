/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

@file:Suppress("NewApi")

package io.github.rwpp.ui

import io.github.rwpp.api.coroutines.GameScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.GameMap
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.game.map.NetworkMap
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedReplayItem(
    val rawReplay: Replay,
    val id: Int,
    val fileName: String,
    val mapName: String,
    val map: GameMap,
    val playerCountStr: String?,
    val versionStr: String,
    val dateTimeStr: String,
    val date: LocalDate? = null
)

@Suppress("ConstantLocale")
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy HH.mm.ss", Locale.getDefault())

@Single
class ReplaysScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()

        val gameMaps by remember { mutableStateOf(game.getAllMaps()) }
        val rawReplays = remember { game.getAllReplays() }
        val parsedReplays = remember(rawReplays, gameMaps) {
            rawReplays.map { parseReplay(it, gameMaps) }
        }
        var displayMaps by remember(parsedReplays) {
            mutableStateOf(parsedReplays.map { it.map }.distinctBy { it.mapName })
        }

        var showMapSelectView by remember { mutableStateOf(false) }
        var selectedFilterMap by remember { mutableStateOf<GameMap?>(null) }
        var lastSelectedIndex by remember { mutableIntStateOf(0) }

        var searchText by remember { mutableStateOf("") }
        var selectedMapCategoryIndex by remember { mutableIntStateOf(0) }
        var selectedTimeCategoryIndex by remember { mutableIntStateOf(0) }
        var selectedSortIndex by remember { mutableIntStateOf(0) }
        var selectedReplayIndex by remember { mutableIntStateOf(0) }

        val mapCategories = remember(selectedFilterMap) {
            listOf(
                "NONE",
                selectedFilterMap?.displayName() ?: "NO MAP SELECTED",
                "SELECT MAP..."
            )
        }
        val timeCategories = remember {
            listOf("ALL TIME", "TODAY", "THIS WEEK", "THIS MONTH")
        }
        val sortOptions = remember {
            listOf("NEWEST FIRST", "OLDEST FIRST", "MAP NAME (A-Z)")
        }

        val filteredReplays = remember(
            parsedReplays, searchText, selectedMapCategoryIndex, selectedFilterMap, selectedTimeCategoryIndex, selectedSortIndex
        ) {
            val today = LocalDate.now()
            val startOfWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val startOfMonth = today.withDayOfMonth(1)

            parsedReplays.filter { replay ->
                val matchesSearch = searchText.isBlank() ||
                        replay.mapName.contains(searchText, ignoreCase = true) ||
                        replay.fileName.contains(searchText, ignoreCase = true) ||
                        replay.dateTimeStr.contains(searchText, ignoreCase = true)

                val matchesMap = when (selectedMapCategoryIndex) {
                    0 -> true
                    1 -> {
                        val filterMap = selectedFilterMap
                        filterMap == null || replay.map.displayName().equals(filterMap.displayName(), ignoreCase = true) ||
                                replay.map.mapName.equals(filterMap.mapName, ignoreCase = true)
                    }
                    else -> true
                }

                val matchesTime = when (selectedTimeCategoryIndex) {
                    1 -> replay.date != null && replay.date == today
                    2 -> replay.date != null && !replay.date.isBefore(startOfWeek) && !replay.date.isAfter(today)
                    3 -> replay.date != null && !replay.date.isBefore(startOfMonth) && !replay.date.isAfter(today)
                    else -> true
                }

                matchesSearch && matchesMap && matchesTime
            }.let { list ->
                when (selectedSortIndex) {
                    0 -> list.sortedWith(
                        compareByDescending<ParsedReplayItem> { it.date ?: LocalDate.MIN }
                            .thenByDescending { it.id }
                    )
                    1 -> list.sortedWith(
                        compareBy<ParsedReplayItem> { it.date ?: LocalDate.MAX }
                            .thenBy { it.id }
                    )
                    2 -> list.sortedBy { it.mapName }
                    else -> list
                }
            }
        }

        LaunchedEffect(filteredReplays.size) {
            if (selectedReplayIndex >= filteredReplays.size) {
                selectedReplayIndex = (filteredReplays.size - 1).coerceAtLeast(0)
            }
        }

        MapViewDialog(
            visible = showMapSelectView,
            onDismissRequest = { showMapSelectView = false },
            lastSelectedIndex = lastSelectedIndex,
            lastSelectedMapType = selectedFilterMap?.mapType ?: GameSetupMapType.skirmishMap,
            displayMaps = displayMaps,
            mapTypeItems = GameSetupMapType.entries,
            onRefresh = {
            },
            onSelectedMap = { index, map ->
                selectedFilterMap = map
                lastSelectedIndex = index
                selectedMapCategoryIndex = 1
            }
        )

        AeroScaledContainer {
            AeroPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroHeader(
                            title = "TACTICAL REPLAY ARCHIVE",
                            tag = "LOGS: ${filteredReplays.size}/${parsedReplays.size}",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        AeroButton(
                            text = readI18n("rwpp.common.exit"),
                            isDanger = true,
                            onClick = { navigator.pop() },
                            clickSound = Sounds.closePopup,
                            modifier = Modifier.width(100.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = "SEARCH_MAP_OR_DATE...",
                            modifier = Modifier.weight(1.2f),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )

                        AeroDropdownMenu(
                            label = "MAP",
                            items = mapCategories,
                            selectedIndex = selectedMapCategoryIndex,
                            onItemSelected = { index ->
                                when (index) {
                                    0 -> {
                                        selectedMapCategoryIndex = 0
                                        selectedFilterMap = null
                                    }
                                    1 -> {
                                        if (selectedFilterMap != null) {
                                            selectedMapCategoryIndex = 1
                                        } else {
                                            showMapSelectView = true
                                        }
                                    }
                                    2 -> {
                                        showMapSelectView = true
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AeroDropdownMenu(
                            label = "TIME",
                            items = timeCategories,
                            selectedIndex = selectedTimeCategoryIndex,
                            onItemSelected = { selectedTimeCategoryIndex = it },
                            modifier = Modifier.weight(0.9f)
                        )

                        AeroDropdownMenu(
                            label = "SORT",
                            items = sortOptions,
                            selectedIndex = selectedSortIndex,
                            onItemSelected = { selectedSortIndex = it },
                            modifier = Modifier.weight(0.9f)
                        )
                    }

                    AeroDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AeroPanel(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                        ) {
                            if (filteredReplays.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "NO TACTICAL LOGS FOUND",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                val listState = rememberLazyListState()
                                LazyColumnScrollbar(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        itemsIndexed(
                                            items = filteredReplays,
                                            key = { _, item -> item.id }
                                        ) { index, item ->
                                            val isSelected = index == selectedReplayIndex
                                            ReplayCardItem(
                                                item = item,
                                                isSelected = isSelected,
                                                onClick = { selectedReplayIndex = index }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AeroPanel(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                        ) {
                            val currentReplay = filteredReplays.getOrNull(selectedReplayIndex)
                            if (currentReplay != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AeroHeader(
                                            title = currentReplay.mapName,
                                            tag = currentReplay.versionStr
                                        )
                                        AeroDivider()

                                        AeroMapPreview(
                                            currentReplay.map,
                                            true
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        ReplayDetailRow(
                                            label = "MAP NAME",
                                            value = currentReplay.mapName
                                        )
                                        AeroDivider()

                                        ReplayDetailRow(
                                            label = "MATCH TIME",
                                            value = currentReplay.dateTimeStr
                                        )
                                        AeroDivider()

                                        ReplayDetailRow(
                                            label = "CLIENT VERSION",
                                            value = currentReplay.versionStr
                                        )
                                        AeroDivider()

                                        if (currentReplay.playerCountStr != null) {
                                            ReplayDetailRow(
                                                label = "CAPACITY / MODE",
                                                value = currentReplay.playerCountStr
                                            )
                                            AeroDivider()
                                        }

                                        ReplayDetailRow(
                                            label = "LOG FILE",
                                            value = currentReplay.fileName,
                                            isSingleLine = false
                                        )
                                    }

                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        AeroButton(
                                            text = "WATCH REPLAY",
                                            onClick = {
                                                @Suppress("UNCHECKED_CAST")
                                                game.watchReplay(currentReplay.rawReplay)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            AeroButton(
                                                text = "SHARE LOG",
                                                accentColor = MaterialTheme.colorScheme.tertiary,
                                                onClick = { },
                                                modifier = Modifier.weight(1f)
                                            )

                                            AeroButton(
                                                text = "DELETE",
                                                isDanger = true,
                                                onClick = { },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "SELECT A TACTICAL LOG TO VIEW DATA",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayDetailRow(
    label: String,
    value: String,
    isSingleLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = if (isSingleLine) 1 else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReplayCardItem(
    item: ParsedReplayItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accentColor : Color = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isHovered) {
        if (isHovered) Sounds.playSound(Sounds.hover2)
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
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(animBgColor)
            .border(1.dp, animBorderColor, shape)
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clickable(interactionSource = interactionSource, indication = null) {
                Sounds.select?.play(GameScope.coroutineContext)
                onClick()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeroBadge(
                    text = item.versionStr,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )

                Text(
                    text = item.dateTimeStr,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = item.mapName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


private val regex = """^(.*?)(?:\s*\[([^\[\]]+)])?\s*\(([^()]+)\)$""".toRegex()
private fun parseReplay(replay: Replay, availableMaps: List<GameMap>): ParsedReplayItem {
    val rawName = replay.displayName()
    val id = replay.id
    val cleanName = rawName.removeSuffix(".replay").trim()

    val match = regex.find(cleanName)

    val (extractedMapName, versionStr, dateTimeStr, playerCountStr) = if (match != null) {
        val mapAndPlayer = match.groupValues[1].trim()
        val version = match.groupValues[2].ifBlank { "UNKNOWN" }
        val dateTime = match.groupValues[3].trim()

        val playerCapRegex = """\((.*?p)\)""".toRegex(RegexOption.IGNORE_CASE)
        val playerMatch = playerCapRegex.find(mapAndPlayer)
        val playerCount = playerMatch?.groupValues?.get(1)

        Quadruple(mapAndPlayer, version, dateTime, playerCount)
    } else {
        Quadruple(cleanName, "UNKNOWN", "N/A", null)
    }

    val matchedMap = availableMaps.firstOrNull {
        it.displayName().equals(extractedMapName, ignoreCase = true) ||
                it.mapName.equals(extractedMapName, ignoreCase = true)
    } ?: NetworkMap(extractedMapName)

    val parsedDate = parseLocalDate(dateTimeStr)

    return ParsedReplayItem(
        rawReplay = replay,
        id = id,
        fileName = rawName,
        mapName = matchedMap.displayName(),
        map = matchedMap,
        playerCountStr = playerCountStr,
        versionStr = versionStr,
        dateTimeStr = dateTimeStr,
        date = parsedDate
    )
}

private fun parseLocalDate(dateStr: String): LocalDate? {
    return try {
        LocalDateTime.parse(dateStr.trim(), DATE_TIME_FORMATTER).toLocalDate()
    } catch (_: Exception) {
        null
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
