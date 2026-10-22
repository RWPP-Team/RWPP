/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rwpp.api.game.Game
import com.corrodinggames.rts.game.units.MovementType
import com.corrodinggames.rts.game.units.UnitTypeInterface
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import org.koin.compose.koinInject

@Composable
fun BanUnitViewDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    lastSelectedUnits: List<UnitTypeInterface>,
    onSelectedUnits: (List<UnitTypeInterface>) -> Unit
) {
    if (!visible) return

    val game = koinInject<Game>()
    val selectedUnits = remember(lastSelectedUnits) {
        SnapshotStateList<UnitTypeInterface>().apply { addAll(lastSelectedUnits) }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        AeroPanel(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // 顶部控制与报头
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroHeader(
                        title = "TACTICAL UNIT RESTRICTIONS",
                        tag = "BANNED: ${selectedUnits.size}",
                        color = MaterialTheme.colorScheme.error
                    )
                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Close),
                        size = 32.dp,
                        iconSize = 16.dp,
                        isDanger = true,
                        clickSound = Sounds.closePopup,
                        onClick = onDismissRequest
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                var filter by remember { mutableStateOf("") }
                val allUnits = remember(filter) {
                    game.getAllUnitTypes().filter {
                        filter.isBlank() ||
                                it.getDisplayName().contains(filter, ignoreCase = true) ||
                                it.getInternalName().contains(filter, ignoreCase = true)
                    }
                }

                AeroTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = "FILTER_UNIT_PROFILES",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                AeroDivider(modifier = Modifier.padding(vertical = 8.dp))

                val movementTypeToUnits = remember(allUnits) {
                    buildMap<MovementType, MutableList<UnitTypeInterface>> {
                        allUnits.forEach { u ->
                            getOrPut(u.movementType) { mutableListOf() }.add(u)
                        }
                    }
                }

                val expandedMap = remember {
                    SnapshotStateMap<MovementType, Boolean>().apply {
                        MovementType.entries.forEach { put(it, false) }
                    }
                }

                val listState = rememberLazyListState()

                LazyColumnScrollbar(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        movementTypeToUnits.forEach { (type, units) ->
                            item(key = "header_${type.name}") {
                                val isAllChecked = remember(units, selectedUnits.size) {
                                    selectedUnits.containsAll(units) && units.isNotEmpty()
                                }
                                val isExpanded = expandedMap[type] ?: false

                                AeroBanUnitHeader(
                                    title = "${type.name} DIRECTORY",
                                    countText = "${units.size} UNITS",
                                    isExpanded = isExpanded,
                                    isChecked = isAllChecked,
                                    onToggleAll = {
                                        if (isAllChecked) {
                                            selectedUnits.removeAll(units.toSet())
                                        } else {
                                            units.forEach { u ->
                                                if (!selectedUnits.contains(u)) selectedUnits.add(u)
                                            }
                                        }
                                    },
                                    onHeaderClick = {
                                        expandedMap[type] = !isExpanded
                                    }
                                )
                            }

                            if (expandedMap[type] == true) {
                                items(
                                    count = units.size,
                                    key = { "unit_${units[it].getInternalName()}" }
                                ) { index ->
                                    val unit = units[index]
                                    val isChecked = selectedUnits.contains(unit)

                                    AeroBanUnitCard(
                                        unit = unit,
                                        isChecked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedUnits.add(unit)
                                            } else {
                                                selectedUnits.remove(unit)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                AeroDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 底部动作控制栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AeroButton(
                        text = "RESET ALL",
                        isDanger = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedUnits.clear()
                            onSelectedUnits(emptyList())
                            onDismissRequest()
                        }
                    )

                    AeroButton(
                        text = "APPLY RESTRICTIONS",
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSelectedUnits(selectedUnits.toList())
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AeroBanUnitHeader(
    title: String,
    countText: String,
    isExpanded: Boolean,
    isChecked: Boolean,
    onToggleAll: () -> Unit,
    onHeaderClick: () -> Unit
) {
    AeroPanel(
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().clickable { onHeaderClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )

                AeroBadge(
                    text = countText,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            AeroToggleTile(
                label = if (isChecked) "BAN ALL" else "ALLOW ALL",
                checked = isChecked,
                onCheckedChange = { onToggleAll() }
            )
        }
    }
}

@Composable
private fun AeroBanUnitCard(
    unit: UnitTypeInterface,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AeroPanel(
        borderColor = if (isChecked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = unit.getDisplayName(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isChecked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "ID: ${unit.getInternalName()}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            AeroToggleTile(
                label = if (isChecked) "BANNED" else "ACTIVE",
                checked = isChecked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
