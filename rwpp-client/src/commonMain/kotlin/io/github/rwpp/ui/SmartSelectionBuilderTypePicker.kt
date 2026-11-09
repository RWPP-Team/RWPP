/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.game.Team
import com.corrodinggames.rts.game.units.UnitTypeInterface
import com.corrodinggames.rts.game.units.custom.CustomUnitMetadata
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.graphics.ActionImages
import io.github.rwpp.logic.selection.SmartSelectionBuilderTypeRules
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

private data class BuilderTypeOption(
    val type: UnitTypeInterface,
    val internalName: String,
    val displayName: String,
)

/** Public entry point for opening the native unit-type picker from HUDs, settings, or probes. */
object SmartSelectionExtraBuilderTypePickerController {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}

@Composable
internal fun SmartSelectionExtraBuilderTypesButton(settings: Settings) {
    val selectedCount = remember(settings.smartSelectionExtraBuilders) {
        SmartSelectionBuilderTypeRules.fromConfig(settings.smartSelectionExtraBuilders).unitTypeIds.size
    }
    AeroButton(
        text = readI18n("rwpp.settings.smartSelection.extraBuilders"),
        subText = "$selectedCount TYPES",
        onClick = SmartSelectionExtraBuilderTypePickerController::open,
        clickSound = Sounds.select,
    )
    SmartSelectionExtraBuilderTypePickerHost(settings)
}

@Composable
internal fun SmartSelectionExtraBuilderTypePickerHost(settings: Settings) {
    SmartSelectionBuilderTypeDialog(
        visible = SmartSelectionExtraBuilderTypePickerController.isOpen,
        settings = settings,
        onDismissRequest = SmartSelectionExtraBuilderTypePickerController::close,
    )
}

@Composable
private fun SmartSelectionBuilderTypeDialog(
    visible: Boolean,
    settings: Settings,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val game = koinInject<Game>()
    val options = remember(game) { loadedBuilderTypeOptions(game) }
    var filter by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(visible) {
        if (visible) {
            selectedIds = SmartSelectionBuilderTypeRules
                .fromConfig(settings.smartSelectionExtraBuilders).unitTypeIds
        }
    }
    val filteredOptions = remember(options, filter) {
        val query = filter.trim()
        options.filter {
            query.isBlank() ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.internalName.contains(query, ignoreCase = true)
        }
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
    ) { dismiss ->
        AeroPanel(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).padding(8.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                AeroHeader(
                    title = "SMART SELECTION BUILDER TYPES",
                    tag = "NATIVE UNIT TYPES",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = readI18n("rwpp.settings.smartSelection.extraBuildersHelp"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AeroTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = readI18n("rwpp.settings.smartSelection.searchUnitTypes"),
                    modifier = Modifier.fillMaxWidth(),
                )
                AeroDivider(Modifier.padding(vertical = 8.dp))

                val listState = rememberLazyListState()
                LazyColumnScrollbar(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (filteredOptions.isEmpty()) {
                            item("no_matches") {
                                Text(
                                    text = readI18n("rwpp.settings.smartSelection.noUnitTypes"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        items(filteredOptions, key = { it.internalName.lowercase() }) { option ->
                            val id = option.internalName.lowercase()
                            val checked = id in selectedIds
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        selectedIds = selectedIds.toMutableSet().apply {
                                            if (checked) remove(id) else add(id)
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                NativeUnitTypePickerIcon(option.type)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        option.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = readI18n(
                                        if (checked) "rwpp.settings.smartSelection.selected"
                                        else "rwpp.settings.smartSelection.notSelected"
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (checked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.widthIn(min = 64.dp, max = 96.dp),
                                )
                            }
                            AeroDivider()
                        }
                    }
                }

                AeroDivider(Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroButton(
                        text = readI18n("rwpp.common.cancel"),
                        modifier = Modifier.weight(1f),
                        isDanger = true,
                        onClick = dismiss,
                        clickSound = Sounds.closePopup,
                    )
                    AeroButton(
                        text = readI18n("rwpp.common.save"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // Keep saved IDs for types not loaded in this session so a temporary
                            // mod-loading difference cannot silently erase a user's choices.
                            val loadedIds = options.mapTo(hashSetOf()) { it.internalName.lowercase() }
                            val savedIds = SmartSelectionBuilderTypeRules
                                .fromConfig(settings.smartSelectionExtraBuilders).unitTypeIds
                            val retainedUnavailable = savedIds - loadedIds
                            settings.smartSelectionExtraBuilders =
                                (selectedIds + retainedUnavailable).sorted().joinToString(",")
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeUnitTypePickerIcon(type: UnitTypeInterface) {
    val image by produceState<ImageBitmap?>(null, type) {
        var attempts = 0
        while (value == null && attempts++ < 25) {
            value = ActionImages.loadUnitType(type, Team.grayTeam, visible = true)
            if (value == null) delay(120.milliseconds)
        }
    }
    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = type.displayName,
            modifier = Modifier.size(42.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Spacer(Modifier.size(42.dp))
    }
}

private fun loadedBuilderTypeOptions(game: Game): List<BuilderTypeOption> {
    val types = ArrayList<UnitTypeInterface>()
    runCatching { game.getAllUnitTypes() }.getOrDefault(emptyList()).forEach(types::add)
    runCatching {
        val active = CustomUnitMetadata.activeCustomUnits
        val backing = active.toArray()
        val count = active.size.coerceIn(0, backing.size)
        List(count) { backing[it] as? CustomUnitMetadata }
    }.getOrDefault(emptyList()).filterNotNull().forEach(types::add)

    return types.mapNotNull { type ->
        val id = runCatching { type.internalName.trim() }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val name = runCatching { type.displayName.trim() }.getOrNull().orEmpty().ifBlank { id }
        BuilderTypeOption(type, id, name)
    }.distinctBy { it.internalName.lowercase() }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.internalName.lowercase() }))
}
