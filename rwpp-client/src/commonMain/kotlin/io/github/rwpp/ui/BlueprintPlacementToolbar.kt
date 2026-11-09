/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import io.github.rwpp.api.config.ConfigIO
import io.github.rwpp.appKoin
import io.github.rwpp.config.SavedBlueprint
import io.github.rwpp.config.Settings
import io.github.rwpp.game.building.BuildingPlanningBlueprintItem
import io.github.rwpp.game.building.validBlueprintItems
import io.github.rwpp.widget.v2.*
import kotlinx.serialization.builtins.ListSerializer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.building.BuildingPlanningController
import io.github.rwpp.game.building.BuildingPlanningMode

/** Compact planning actions leave the map visible. */
@Composable
internal fun GameScreen.BlueprintPlacementToolbar(
    visible: Boolean,
    libraryOpen: Boolean,
    onLibraryOpenChanged: (Boolean) -> Unit,
) {
    if (!visible) return
    var state by remember { mutableStateOf(BuildingPlanningController.snapshot) }
    OnGameUpdate {
        val next = BuildingPlanningController.snapshot
        if (state != next) state = next
    }
    val selecting = state.mode == BuildingPlanningMode.SELECT_BLUEPRINT
    val placing = state.mode == BuildingPlanningMode.PLACE_BLUEPRINT
    if (!selecting && !placing) return
    if (libraryOpen) {
        DisposableEffect(Unit) { onDispose { onLibraryOpenChanged(false) } }
        BlueprintLibrary(if (placing) state.blueprint else emptyList(), onDismiss = { onLibraryOpenChanged(false) })
    }
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            AeroIconButton(
                rememberVectorPainter(Icons.Default.List), { onLibraryOpenChanged(true) },
                contentDescription = readI18n("rwpp.planning.library"), size = 44.dp,
            )
            if (placing) Text(
                readI18n("rwpp.planning.buildingCount", state.blueprint.size),
                color = MaterialTheme.colorScheme.onSurface,
            )
            AeroIconButton(
                rememberVectorPainter(Icons.Default.Close), BuildingPlanningController::cancelBlueprint,
                contentDescription = readI18n("rwpp.common.cancel"), size = 44.dp, isDanger = true
            )
            if (placing) {
                AeroIconButton(
                    rememberVectorPainter(Icons.Default.Refresh), BuildingPlanningController::beginBlueprintSelection,
                    contentDescription = readI18n("rwpp.planning.independentReselect"), size = 44.dp
                )
                AeroIconButton(
                    rememberVectorPainter(Icons.Default.Build), BuildingPlanningController::matchBlueprintBuilders,
                    contentDescription = readI18n("rwpp.planning.independentBuilders"), size = 44.dp
                )
                AeroIconButton(
                    rememberVectorPainter(Icons.Default.Check),
                    { if (state.canConfirm) BuildingPlanningController.confirmBlueprint() },
                    modifier = Modifier.alpha(if (state.canConfirm) 1f else 0.4f)
                        .semantics { if (!state.canConfirm) disabled() },
                    contentDescription = readI18n("rwpp.common.confirm"), size = 44.dp,
                    accentColor = if (state.canConfirm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


@Composable
private fun BlueprintLibrary(current: List<BuildingPlanningBlueprintItem>, onDismiss: () -> Unit) {
    val settings = remember { appKoin.get<Settings>() }
    var saved by remember { mutableStateOf(settings.savedBlueprints) }
    var name by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf<String?>(null) }
    val selected = saved.firstOrNull { it.name == selectedName }

    fun store(blueprints: List<SavedBlueprint>) {
        require(blueprints.size <= 128 && blueprints.map { it.name }.distinct().size == blueprints.size)
        require(blueprints.all { it.name.length in 1..64 && it.name.isNotBlank() &&
                it.name.none(Char::isISOControl) && validBlueprintItems(it.items) })
        settings.savedBlueprints = blueprints
        appKoin.get<ConfigIO>().saveConfig(settings)
        saved = blueprints
    }

    AnimatedDialog(onDismissRequest = onDismiss) { dismiss ->
        AeroPanel(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeroHeader(title = "BLUEPRINT LIBRARY")
                if (validBlueprintItems(current)) {
                    AeroTextField(name, { name = it.take(64) }, readI18n("rwpp.planning.blueprintName"))
                    val newName = name.trim()
                    AeroButton(readI18n("rwpp.common.save"), {
                        store(saved + SavedBlueprint(newName, current))
                        selectedName = newName
                        name = ""
                    }, enabled = newName.isNotBlank() && newName.none(Char::isISOControl) &&
                            saved.size < 128 && saved.none { it.name == newName })
                }
                PortableConfigActions(
                    "rwpp-blueprints.toml", ListSerializer(SavedBlueprint.serializer()),
                    snapshot = { saved },
                    apply = { imported ->
                        require(imported.map { it.name }.distinct().size == imported.size)
                        val existingNames = saved.mapTo(mutableSetOf()) { it.name }
                        require(imported.none { it.name in existingNames }) { readI18n("rwpp.planning.duplicateName") }
                        store(saved + imported)
                    },
                )
                saved.forEach { blueprint ->
                    AeroButton(
                        "${blueprint.name} · ${blueprint.items.size}",
                        { selectedName = blueprint.name },
                        modifier = Modifier.fillMaxWidth(),
                        accentColor = if (selectedName == blueprint.name) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                if (selected != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AeroButton(readI18n("rwpp.planning.loadBlueprint"), {
                            BuildingPlanningController.loadBlueprint(selected.items)
                            dismiss()
                        }, modifier = Modifier.weight(1f))
                        AeroButton(readI18n("rwpp.common.delete"), {
                            store(saved.filterNot { it.name == selected.name })
                            selectedName = null
                        }, isDanger = true, modifier = Modifier.weight(1f))
                    }
                }
                AeroButton(readI18n("rwpp.common.close"), dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
