/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

@file:Suppress("NewApi")

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
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
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.game.map.Replay
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.replay.*
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.ResponsiveFilterChoices
import io.github.rwpp.widget.ResponsiveSelectionBody
import io.github.rwpp.widget.usesCompactSelectionLayout
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import java.time.LocalDate

@Single
class ReplaysScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val compact = koinInject<io.github.rwpp.AppContext>().isAndroid() ||
                usesCompactSelectionLayout(LocalWindowManager.current)

        val gameMaps = remember { mutableStateListOf<GameMap>() }
        val rawReplays = remember { mutableStateListOf<Replay>() }
        val parsedReplays = remember { mutableStateListOf<ParsedReplayItem>() }
        var mapsLoading by remember { mutableStateOf(true) }
        var mapCatalogRefresh by remember { mutableIntStateOf(0) }
        var mapCatalogError by remember { mutableStateOf<String?>(null) }
        var replayCatalogLoading by remember { mutableStateOf(true) }
        var catalogLoadError by remember { mutableStateOf<String?>(null) }
        var replayCatalogRefresh by remember { mutableIntStateOf(0) }
        var replayCatalogVersion by remember { mutableIntStateOf(0) }
        var replayMapLookup by remember { mutableStateOf(ReplayMapLookup(emptyList())) }
        var mapLookupGeneration by remember { mutableIntStateOf(0) }
        var replaySourceEpoch by remember { mutableIntStateOf(0) }

        LaunchedEffect(mapCatalogRefresh) {
            gameMaps.clear()
            mapsLoading = true
            mapCatalogError = null
            var completed = false
            try {
                game.getMapPages(refresh = mapCatalogRefresh > 0).collect { page -> gameMaps.addAll(page.items) }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mapCatalogError = failure.message ?: readI18n("rwpp.catalog.unknownError")
            } finally {
                mapsLoading = false
                if (completed) {
                    val mapSnapshot = gameMaps.toList()
                    replayMapLookup = withContext(Dispatchers.Default) { ReplayMapLookup(mapSnapshot) }
                    mapLookupGeneration++
                }
            }
        }

        LaunchedEffect(replayCatalogRefresh) {
            replaySourceEpoch++
            rawReplays.clear()
            parsedReplays.clear()
            replayCatalogVersion++
            replayCatalogLoading = true
            catalogLoadError = null
            var completed = false
            try {
                game.getReplayPages(pageSize = 256, refresh = replayCatalogRefresh > 0).collect { page ->
                    var lookupForPage = replayMapLookup
                    var parsedPage: List<ParsedReplayItem>
                    while (true) {
                        parsedPage = withContext(Dispatchers.Default) {
                            page.items.map { parseReplay(it, lookupForPage) }
                        }
                        val latestLookup = replayMapLookup
                        if (latestLookup === lookupForPage) break
                        lookupForPage = latestLookup
                    }
                    rawReplays.addAll(page.items)
                    parsedReplays.addAll(parsedPage)
                    replayCatalogVersion++
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                catalogLoadError = failure.message ?: readI18n("rwpp.catalog.unknownError")
            } finally {
                replayCatalogLoading = false
                if (!completed) replayCatalogVersion++
            }
        }

        LaunchedEffect(mapLookupGeneration, replaySourceEpoch) {
            if (mapLookupGeneration == 0) return@LaunchedEffect
            val lookup = replayMapLookup
            val capturedMapGeneration = mapLookupGeneration
            val capturedReplayEpoch = replaySourceEpoch
            val capturedReplays = rawReplays.toList()
            for (firstIndex in capturedReplays.indices step 256) {
                val endIndex = (firstIndex + 256).coerceAtMost(capturedReplays.size)
                val replayChunk = capturedReplays.subList(firstIndex, endIndex).toList()
                val parsedChunk = withContext(Dispatchers.Default) {
                    replayChunk.map { parseReplay(it, lookup) }
                }
                if (capturedMapGeneration != mapLookupGeneration || capturedReplayEpoch != replaySourceEpoch) {
                    return@LaunchedEffect
                }
                Snapshot.withMutableSnapshot {
                    parsedChunk.forEachIndexed { offset, parsed ->
                        val index = firstIndex + offset
                        val expectedName = replayChunk[offset].name
                        if (index < rawReplays.size && rawReplays[index].name == expectedName &&
                            parsedReplays.getOrNull(index)?.rawReplay?.name == expectedName
                        ) {
                            parsedReplays[index] = parsed
                        }
                    }
                }
            }
            if (capturedMapGeneration == mapLookupGeneration && capturedReplayEpoch == replaySourceEpoch) {
                replayCatalogVersion++
            }
        }
        val displayMaps by remember {
            derivedStateOf { parsedReplays.map { it.map }.distinctBy { it.mapName } }
        }
        val refreshReplayCatalog: () -> Unit = { replayCatalogRefresh++ }

        var showMapSelectView by remember { mutableStateOf(false) }
        var selectedFilterMap by remember { mutableStateOf<GameMap?>(null) }
        var lastSelectedIndex by remember { mutableIntStateOf(0) }

        var searchText by remember { mutableStateOf("") }
        var selectedMapCategoryIndex by remember { mutableIntStateOf(0) }
        var selectedTimeCategoryIndex by remember { mutableIntStateOf(0) }
        var selectedSortIndex by remember { mutableIntStateOf(0) }
        var selectedReplayIndex by remember { mutableIntStateOf(0) }
        var replaysToDelete by remember { mutableStateOf<List<Replay>?>(null) }
        var replayToRename by remember { mutableStateOf<Replay?>(null) }
        var renameDraft by remember { mutableStateOf("") }
        var batchSelection by remember { mutableStateOf(ReplayBatchSelection()) }
        val selectionMode = batchSelection.active
        val selectedReplayNames = batchSelection.selectedNames
        val appContext = koinInject<io.github.rwpp.AppContext>()
        val compactReplayListState = rememberLazyListState()
        val desktopReplayListState = rememberLazyListState()

        val requestDelete: (List<Replay>) -> Unit = { replays ->
            replays.distinctBy { it.name }.takeIf { it.isNotEmpty() }?.let { replaysToDelete = it }
        }
        val requestRename: (Replay) -> Unit = { replay ->
            replayToRename = replay
            renameDraft = if (replay.name.endsWith(".replay", ignoreCase = true)) {
                replay.name.dropLast(".replay".length)
            } else {
                replay.name
            }
        }
        val toggleReplaySelection: (Replay) -> Unit = { replay ->
            batchSelection = batchSelection.tap(replay.name)
        }
        val longPressReplay: (Replay) -> Unit = { replay ->
            batchSelection = batchSelection.longPress(replay.name)
        }

        val mapCategories = remember(selectedFilterMap) {
            listOf(
                readI18n("rwpp.replay.mapAll"),
                selectedFilterMap?.displayName() ?: readI18n("rwpp.replay.noMapSelected"),
                readI18n("rwpp.replay.selectMap"),
            )
        }
        val timeCategories = remember {
            listOf(
                readI18n("rwpp.replay.allTime"),
                readI18n("rwpp.replay.today"),
                readI18n("rwpp.replay.thisWeek"),
                readI18n("rwpp.replay.thisMonth"),
            )
        }
        val sortOptions = remember {
            listOf(
                readI18n("rwpp.replay.newestFirst"),
                readI18n("rwpp.replay.oldestFirst"),
                readI18n("rwpp.replay.mapNameSort"),
            )
        }

        val filteredReplays = remember(
            replayCatalogVersion,
            searchText,
            selectedMapCategoryIndex,
            selectedFilterMap,
            selectedTimeCategoryIndex,
            selectedSortIndex
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
                        filterMap == null || replay.map.displayName()
                            .equals(filterMap.displayName(), ignoreCase = true) ||
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

        AnimatedAlertDialog(replaysToDelete != null, { replaysToDelete = null }) { dismiss ->
            AeroPanel(modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val pending = replaysToDelete.orEmpty()
                    AeroHeader(title = if (pending.size == 1) "DELETE REPLAY" else "DELETE REPLAYS")
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth()
                            .heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            if (pending.size == 1) {
                                readI18n("rwpp.replay.deleteConfirm") + "\n" + pending.single().name
                            } else {
                                readI18n("rwpp.replay.deleteManyConfirm", pending.size)
                            }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroButton(
                            readI18n("rwpp.common.cancel"), onClick = dismiss, modifier = Modifier.weight(1f)
                        )
                        AeroButton(
                            readI18n("rwpp.common.delete"),
                            isDanger = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val failures = mutableListOf<String>()
                                val deletedNames = mutableSetOf<String>()
                                pending.forEach { replay ->
                                    runCatching { ReplayFiles.delete(replay) }
                                        .onSuccess { deletedNames += replay.name }
                                        .onFailure { failures += "${replay.name}: ${it.message.orEmpty()}" }
                                }
                                refreshReplayCatalog()
                                batchSelection = batchSelection.afterDeleting(
                                    deletedNames = deletedNames,
                                    remainingNames = rawReplays.mapTo(mutableSetOf()) { it.name } - deletedNames,
                                )
                                if (failures.isNotEmpty()) {
                                    val details = failures.take(10).joinToString("\n")
                                    val overflow = failures.size - 10
                                    val message = if (overflow > 0) "$details\n… $overflow more" else details
                                    UI.showWarning(readI18n("rwpp.replay.fileError", message))
                                }
                                replaysToDelete = null
                                dismiss()
                            })
                    }
                }
            }
        }

        AnimatedAlertDialog(replayToRename != null, { replayToRename = null }) { dismiss ->
            AeroPanel(modifier = Modifier.widthIn(max = 440.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AeroHeader(title = "RENAME REPLAY")
                    Text(readI18n("rwpp.replay.renamePrompt"))
                    AeroTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        placeholder = readI18n("rwpp.replay.newName"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroButton(readI18n("rwpp.common.cancel"), onClick = dismiss)
                        AeroButton(readI18n("rwpp.common.confirm"), onClick = {
                            val replay = replayToRename
                            if (replay != null) {
                                val oldName = replay.name
                                runCatching { ReplayFiles.rename(replay, renameDraft) }
                                    .onSuccess { newName ->
                                        refreshReplayCatalog()
                                        if (oldName in selectedReplayNames) {
                                            batchSelection = batchSelection.copy(
                                                selectedNames = (selectedReplayNames - oldName) + newName,
                                            )
                                        }
                                    }
                                    .onFailure {
                                        UI.showWarning(readI18n("rwpp.replay.fileError", it.message.orEmpty()))
                                    }
                            }
                            replayToRename = null
                            dismiss()
                        })
                    }
                }
            }
        }
        val shareReplay: (Replay) -> Unit = { replay ->
            runCatching {
                appContext.shareFile(
                    replay.name,
                    ReplayFiles.sourcePath(replay),
                    { ReplayFiles.open(replay) }) { result ->
                    result.onFailure { UI.showWarning(readI18n("rwpp.replay.fileError", it.message.orEmpty())) }
                }
            }.onFailure { UI.showWarning(readI18n("rwpp.replay.fileError", it.message.orEmpty())) }
        }

        MapViewDialog(
            visible = showMapSelectView,
            onDismissRequest = { showMapSelectView = false },
            lastSelectedIndex = lastSelectedIndex,
            lastSelectedMapType = selectedFilterMap?.mapType ?: GameSetupMapType.skirmishMap,
            displayMaps = displayMaps,
            isLoading = mapsLoading,
            loadError = mapCatalogError,
            mapTypeItems = GameSetupMapType.entries,
            onRefresh = { mapCatalogRefresh++ },
            onSelectedMap = { index, map ->
                selectedFilterMap = map
                lastSelectedIndex = index
                selectedMapCategoryIndex = 1
            }
        )

        AeroScaledContainer {
            AeroPanel(
                modifier = Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp),
            ) {
                ResponsiveSelectionBody(
                    compact = compact,
                    compactState = compactReplayListState,
                    modifier = Modifier.fillMaxSize().padding(if (compact) 6.dp else 0.dp),
                    compactContent = {
                        item(key = "replay-header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroHeader(
                                    title = "REPLAYS",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = { navigator.pop() },
                                    clickSound = Sounds.closePopup,
                                    modifier = Modifier.width(92.dp),
                                )
                            }
                            Text(
                                text = readI18n("rwpp.replay.logCount", filteredReplays.size, parsedReplays.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        item(key = "replay-selection-controls") {
                            ReplaySelectionControls(
                                compact = true,
                                selectionMode = selectionMode,
                            )
                        }
                        item(key = "replay-search") {
                            AeroTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = readI18n("rwpp.replay.searchMapOrDate"),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                        item(key = "replay-map-filter") {
                            ResponsiveFilterChoices(
                                label = readI18n("rwpp.common.map").uppercase(),
                                items = mapCategories,
                                selectedIndex = selectedMapCategoryIndex,
                                onItemSelected = { index ->
                                    when (index) {
                                        0 -> {
                                            selectedMapCategoryIndex = 0
                                            selectedFilterMap = null
                                        }

                                        1 -> if (selectedFilterMap != null) {
                                            selectedMapCategoryIndex = 1
                                        } else {
                                            showMapSelectView = true
                                        }

                                        2 -> showMapSelectView = true
                                    }
                                },
                            )
                        }
                        item(key = "replay-time-filter") {
                            ResponsiveFilterChoices(
                                label = readI18n("rwpp.replay.timeFilter").uppercase(),
                                items = timeCategories,
                                selectedIndex = selectedTimeCategoryIndex,
                                onItemSelected = { selectedTimeCategoryIndex = it },
                            )
                        }
                        item(key = "replay-sort-filter") {
                            ResponsiveFilterChoices(
                                label = readI18n("rwpp.replay.sortFilter").uppercase(),
                                items = sortOptions,
                                selectedIndex = selectedSortIndex,
                                onItemSelected = { selectedSortIndex = it },
                            )
                        }
                        item(key = "replay-result-heading") {
                            AeroDivider()
                            AeroHeader(title = "TACTICAL LOGS")
                        }
                        if (filteredReplays.isEmpty()) {
                            item(key = "replay-empty") {
                                Text(
                                    text = catalogLoadError?.let {
                                        readI18n("rwpp.catalog.failed", it)
                                    }
                                        ?: if (replayCatalogLoading) {
                                            readI18n("rwpp.catalog.loading")
                                        } else readI18n("rwpp.replay.noneFound"),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = filteredReplays,
                                key = { _, item -> "replay-${item.id}" },
                            ) { index, item ->
                                ReplayCardItem(
                                    item = item,
                                    isSelected = index == selectedReplayIndex,
                                    selectionMode = selectionMode,
                                    isBatchSelected = item.rawReplay.name in selectedReplayNames,
                                    compact = true,
                                    onClick = {
                                        if (selectionMode) toggleReplaySelection(item.rawReplay)
                                        else selectedReplayIndex = index
                                    },
                                    onLongClick = { longPressReplay(item.rawReplay) },
                                )
                            }
                            item(key = "replay-selected-details") {
                                ReplayDetailsPanel(
                                    replay = filteredReplays.getOrNull(selectedReplayIndex),
                                    game = game,
                                    compact = true,
                                    onShare = shareReplay,
                                    onRename = requestRename,
                                    selectedCount = selectedReplayNames.size,
                                    onDeleteSelected = {
                                        if (selectionMode) {
                                            requestDelete(rawReplays.filter { it.name in selectedReplayNames })
                                        } else {
                                            requestDelete(listOfNotNull(filteredReplays.getOrNull(selectedReplayIndex)?.rawReplay))
                                        }
                                    },
                                )
                            }
                        }
                    },
                    desktopContent = {
                        // Restore the original desktop toolbar and split list/details view.
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroHeader(
                                    title = "TACTICAL REPLAY ARCHIVE",
                                    tag = "LOGS: ${filteredReplays.size}/${parsedReplays.size}",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                AeroButton(
                                    text = readI18n("rwpp.common.exit"),
                                    isDanger = true,
                                    onClick = { navigator.pop() },
                                    clickSound = Sounds.closePopup,
                                    modifier = Modifier.width(100.dp),
                                )
                            }
                            ReplaySelectionControls(
                                compact = false,
                                selectionMode = selectionMode,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroTextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    placeholder = readI18n("rwpp.replay.searchMapOrDate"),
                                    modifier = Modifier.weight(1.2f),
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.common.map").uppercase(),
                                    items = mapCategories,
                                    selectedIndex = selectedMapCategoryIndex,
                                    onItemSelected = { index ->
                                        when (index) {
                                            0 -> {
                                                selectedMapCategoryIndex = 0
                                                selectedFilterMap = null
                                            }

                                            1 -> if (selectedFilterMap != null) {
                                                selectedMapCategoryIndex = 1
                                            } else {
                                                showMapSelectView = true
                                            }

                                            2 -> showMapSelectView = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.replay.timeFilter").uppercase(),
                                    items = timeCategories,
                                    selectedIndex = selectedTimeCategoryIndex,
                                    onItemSelected = { selectedTimeCategoryIndex = it },
                                    modifier = Modifier.weight(0.9f),
                                )
                                AeroDropdownMenu(
                                    label = readI18n("rwpp.replay.sortFilter").uppercase(),
                                    items = sortOptions,
                                    selectedIndex = selectedSortIndex,
                                    onItemSelected = { selectedSortIndex = it },
                                    modifier = Modifier.weight(0.9f),
                                )
                            }
                            AeroDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AeroPanel(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
                                    if (filteredReplays.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = catalogLoadError?.let {
                                                    readI18n("rwpp.catalog.failed", it)
                                                }
                                                    ?: if (replayCatalogLoading) {
                                                        readI18n("rwpp.catalog.loading")
                                                    } else readI18n("rwpp.replay.noneFound"),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                    } else {
                                        LazyColumnScrollbar(
                                            state = desktopReplayListState,
                                            modifier = Modifier.fillMaxSize(),
                                        ) {
                                            LazyColumn(
                                                state = desktopReplayListState,
                                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                itemsIndexed(
                                                    items = filteredReplays,
                                                    key = { _, item -> item.id },
                                                ) { index, item ->
                                                    ReplayCardItem(
                                                        item = item,
                                                        isSelected = index == selectedReplayIndex,
                                                        selectionMode = selectionMode,
                                                        isBatchSelected = item.rawReplay.name in selectedReplayNames,
                                                        compact = false,
                                                        onClick = {
                                                            if (selectionMode) toggleReplaySelection(item.rawReplay)
                                                            else selectedReplayIndex = index
                                                        },
                                                        onLongClick = { longPressReplay(item.rawReplay) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                ReplayDetailsPanel(
                                    replay = filteredReplays.getOrNull(selectedReplayIndex),
                                    game = game,
                                    compact = false,
                                    onShare = shareReplay,
                                    onRename = requestRename,
                                    selectedCount = selectedReplayNames.size,
                                    onDeleteSelected = {
                                        if (selectionMode) {
                                            requestDelete(rawReplays.filter { it.name in selectedReplayNames })
                                        } else {
                                            requestDelete(listOfNotNull(filteredReplays.getOrNull(selectedReplayIndex)?.rawReplay))
                                        }
                                    },
                                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReplaySelectionControls(
    compact: Boolean,
    selectionMode: Boolean,
) {
    if (compact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = readI18n(if (selectionMode) "rwpp.replay.batchHintActive" else "rwpp.replay.batchHint"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = readI18n(if (selectionMode) "rwpp.replay.batchHintActive" else "rwpp.replay.batchHint"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReplayDetailsPanel(
    replay: ParsedReplayItem?,
    game: Game,
    compact: Boolean,
    onShare: (Replay) -> Unit,
    onRename: (Replay) -> Unit,
    selectedCount: Int,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AeroPanel(modifier = if (compact) modifier.fillMaxWidth() else modifier.fillMaxSize()) {
        if (replay == null) {
            Box(
                modifier = if (compact) Modifier.fillMaxWidth().heightIn(min = 88.dp) else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = readI18n("rwpp.replay.selectLog"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReplayInfo(replay, compact = true)
                ReplayActions(replay, game, onShare, onRename, selectedCount, onDeleteSelected)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                ReplayInfo(replay, compact = false)
                ReplayActions(replay, game, onShare, onRename, selectedCount, onDeleteSelected)
            }
        }
    }
}

@Composable
private fun ReplayInfo(replay: ParsedReplayItem, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeroHeader(
            title = replay.mapName,
            tag = if (compact) null else replay.versionStr,
        )
        AeroDivider()
        AeroMapPreview(replay.map, true)
        Spacer(modifier = Modifier.height(6.dp))
        ReplayDetailRow(label = readI18n("rwpp.replay.mapName"), value = replay.mapName)
        AeroDivider()
        ReplayDetailRow(label = readI18n("rwpp.replay.matchTime"), value = replay.dateTimeStr)
        AeroDivider()
        ReplayDetailRow(label = readI18n("rwpp.replay.clientVersion"), value = replay.versionStr)
        AeroDivider()
        if (replay.playerCountStr != null) {
            ReplayDetailRow(label = readI18n("rwpp.replay.capacityMode"), value = replay.playerCountStr)
            AeroDivider()
        }
        ReplayDetailRow(label = readI18n("rwpp.replay.logFile"), value = replay.fileName, isSingleLine = false)
    }
}

@Composable
private fun ReplayActions(
    replay: ParsedReplayItem,
    game: Game,
    onShare: (Replay) -> Unit,
    onRename: (Replay) -> Unit,
    selectedCount: Int,
    onDeleteSelected: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AeroButton(
            text = readI18n("rwpp.replay.watch"),
            onClick = {
                AeroReplaySeekState.prepareReplay(replay.rawReplay.name)
                @Suppress("UNCHECKED_CAST")
                game.watchReplay(replay.rawReplay)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AeroButton(
                text = readI18n("rwpp.replay.rename"),
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = { onRename(replay.rawReplay) },
                modifier = Modifier.weight(1f),
            )
            AeroButton(
                text = readI18n("rwpp.replay.share"),
                accentColor = MaterialTheme.colorScheme.tertiary,
                onClick = { onShare(replay.rawReplay) },
                modifier = Modifier.weight(1f),
            )

            AeroButton(
                text = if (selectedCount > 1)
                    readI18n("rwpp.replay.deleteSelected", selectedCount)
                else readI18n("rwpp.common.delete"),
                isDanger = true,
                onClick = { onDeleteSelected() },
                modifier = Modifier.weight(1f),
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReplayCardItem(
    item: ParsedReplayItem,
    isSelected: Boolean,
    selectionMode: Boolean = false,
    isBatchSelected: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val accentColor: Color = MaterialTheme.colorScheme.primary
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
            .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 4.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    Sounds.playSound(Sounds.select)
                    onClick()
                },
                onLongClick = {
                    Sounds.playSound(Sounds.select)
                    onLongClick()
                },
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
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

                if (selectionMode) {
                    AeroBadge(
                        text = if (isBatchSelected) "SELECTED" else "SELECT",
                        color = if (isBatchSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                }

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
