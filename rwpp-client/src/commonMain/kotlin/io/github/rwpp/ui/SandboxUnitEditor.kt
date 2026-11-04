/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.sandbox.*
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.Dispatchers

@Composable
internal fun SandboxUnitEditorControls(snapshot: SandboxUnitDataSnapshot) {
    var showUnitData by remember(snapshot.selectionIds) { mutableStateOf(false) }
    var explicitSampleVersion by remember(snapshot.selectionIds) { mutableIntStateOf(0) }
    var displayedSnapshot by remember(snapshot.selectionIds) { mutableStateOf(snapshot) }
    val currentSelectionIds = rememberUpdatedState(snapshot.selectionIds)
    LaunchedEffect(snapshot) {
        if (sandboxSnapshotMatchesSelection(snapshot, displayedSnapshot.selectionIds)) displayedSnapshot = snapshot
    }
    val selectionIds = displayedSnapshot.selectionIds
    val owners = displayedSnapshot.teams
    val ownerIndex = displayedSnapshot.ownerId?.let { id -> owners.indexOfFirst { it.id == id } } ?: -1
    var showAttachments by remember(selectionIds) { mutableStateOf(false) }
    var attachmentOptions by remember(selectionIds) { mutableStateOf<SandboxAttachmentOptionsSnapshot?>(null) }
    val refreshAttachmentOptions = {
        val hostIds = displayedSnapshot.attachmentHostIds
        requestSandboxAttachmentOptions(hostIds, Dispatchers.Main) { refreshed ->
            if (sandboxSnapshotMatchesSelection(displayedSnapshot, currentSelectionIds.value)) {
                attachmentOptions = refreshed
            }
        }
    }
    val refreshSnapshot = {
        requestSandboxUnitDataSnapshot(selectionIds, Dispatchers.Main) { refreshed ->
            if (refreshed != null && sandboxSnapshotMatchesSelection(refreshed, selectionIds) &&
                sandboxSnapshotMatchesSelection(refreshed, currentSelectionIds.value)
            ) {
                displayedSnapshot = refreshed
                explicitSampleVersion++
            }
        }
    }
    val refreshAndOpen = {
        requestSandboxUnitDataSnapshot(selectionIds, Dispatchers.Main) { refreshed ->
            if (refreshed != null && sandboxSnapshotMatchesSelection(refreshed, selectionIds) &&
                sandboxSnapshotMatchesSelection(refreshed, currentSelectionIds.value)
            ) {
                displayedSnapshot = refreshed
                explicitSampleVersion++
                showUnitData = true
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            if (owners.isNotEmpty()) {
                AeroDropdownMenu(
                    label = readI18n("rwpp.sandbox.editor.owner"),
                    items = owners.map { it.name },
                    selectedIndex = ownerIndex,
                    onItemSelected = { index ->
                        owners.getOrNull(index)?.let { submitSandboxOwnerChange(selectionIds, it.id) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            AeroDropdownMenu(
                label = readI18n("rwpp.sandbox.editor.allianceGroup"),
                items = (0..4).map(Int::toString),
                selectedIndex = displayedSnapshot.allianceGroup,
                onItemSelected = { submitSandboxAllianceChange(selectionIds, it) },
                modifier = Modifier.weight(1f),
            )
            AeroButton(
                readI18n("rwpp.sandbox.editor.unitData"),
                onClick = refreshAndOpen,
                modifier = Modifier.weight(1f),
            )
        }
        if (sandboxAttachmentActionAvailable(displayedSnapshot)) {
            AeroButton(
                text = readI18n("rwpp.sandbox.editor.manageAttachments"),
                onClick = {
                    attachmentOptions = null
                    showAttachments = true
                    refreshAttachmentOptions()
                },
                accentColor = Color(0xFF62C8F2),
                subText = "UNIT ATTACHMENT",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    SandboxAttachmentDialog(
        visible = showAttachments,
        options = attachmentOptions,
        onRefresh = refreshAttachmentOptions,
        onDismiss = { showAttachments = false },
    )

    SandboxUnitDataDialog(
        visible = showUnitData,
        snapshot = displayedSnapshot,
        onDismiss = { showUnitData = false },
        onRefresh = refreshSnapshot,
        sampleVersion = explicitSampleVersion,
        onApply = { targetUnitIds, edits ->
            submitSandboxUnitData(targetUnitIds, edits.first, edits.second)
            showUnitData = false
        },
    )
}

@Composable
private fun SandboxUnitDataDialog(
    visible: Boolean,
    snapshot: SandboxUnitDataSnapshot,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    sampleVersion: Int,
    onApply: (List<Long>, Pair<Map<SandboxUnitDataField, Float>, List<SandboxUnitResourceEdit>>) -> Unit,
) {
    val targets = snapshot.editTargets
    var targetIndex by remember(snapshot.selectionIds) { mutableIntStateOf(0) }
    val target = targets.getOrNull(targetIndex) ?: targets.firstOrNull()
    val dataValues = remember(snapshot.selectionIds, target?.key, sampleVersion) {
        target?.dataValues.orEmpty()
    }
    val valueFields = dataValues.map { it.field }
    val initialNumbers = dataValues.map { it.value }
    val values = remember(snapshot.selectionIds, target?.key, sampleVersion) {
        mutableStateListOf<String>().apply { addAll(initialNumbers.map(Float::toString)) }
    }
    val resourceFields = remember(snapshot.selectionIds, target?.key, sampleVersion) {
        target?.resources.orEmpty()
    }
    val resourceValues = remember(snapshot.selectionIds, target?.key, sampleVersion) {
        mutableStateListOf<String>().apply { addAll(resourceFields.map { it.initialValue }.map(Double::toString)) }
    }
    val valuesValid = valueFields.size == values.size && values.indices.all { index ->
        sandboxNumericTextAccepts(valueFields[index], values[index])
    }
    val resourceValuesValid = resourceFields.size == resourceValues.size && resourceValues.indices.all { index ->
        resourceValues[index].toDoubleOrNull()?.let { sandboxSplitResourceAmount(it) != null } == true
    }
    val canApply = (valueFields.isNotEmpty() || resourceFields.isNotEmpty()) && valuesValid && resourceValuesValid
    AnimatedAlertDialog(visible = visible, onDismissRequest = onDismiss) { dismiss ->
        AeroPanel(Modifier.fillMaxWidth(0.96f).heightIn(max = 600.dp).padding(6.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeroHeader("UNIT DATA", tag = "SANDBOX")
                if (targets.size > 1) AeroDropdownMenu(
                    label = readI18n("rwpp.sandbox.editor.target"),
                    items = targets.map { target ->
                        val label = readI18n(target.labelKey)
                        target.labelUnitId?.let { id ->
                            val name = target.labelUnitName
                            if (name == null) "$label #$id" else "$label $name #$id"
                        } ?: "$label (${target.unitIds.size})"
                    },
                    selectedIndex = targetIndex.coerceIn(0, targets.lastIndex),
                    onItemSelected = { targetIndex = it; onRefresh() },
                    modifier = Modifier.fillMaxWidth(),
                )
                valueFields.forEachIndexed { index, field ->
                    AeroTextField(
                        value = values[index],
                        onValueChange = { raw ->
                            if (sandboxNumericInputAllowed(raw)) values[index] = raw
                        },
                        placeholder = readI18n(field.labelKey),
                        modifier = Modifier.fillMaxWidth(),
                        // Keep the text keyboard available for signs and scientific notation on Android.
                        typeInNumberOnly = false,
                    )
                }
                if (resourceFields.isNotEmpty()) {
                    AeroHeader(readI18n("rwpp.sandbox.editor.customResources"), tag = "MOD")
                    resourceFields.forEachIndexed { index, field ->
                        AeroTextField(
                            value = resourceValues[index],
                            onValueChange = { raw ->
                                if (sandboxNumericInputAllowed(raw)) resourceValues[index] = raw
                            },
                            placeholder = field.label,
                            modifier = Modifier.fillMaxWidth(),
                            // Native resource values may be negative; a text keyboard keeps '-' available
                            // on Android while this editor still accepts only numeric characters.
                            typeInNumberOnly = false,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AeroButton(
                        readI18n("rwpp.common.cancel"),
                        onClick = dismiss,
                        modifier = Modifier.weight(1f),
                    )
                    AeroButton(
                        readI18n("rwpp.common.confirm"),
                        onClick = {
                            val unitEdits = sandboxChangedUnitDataEdits(
                                valueFields,
                                initialNumbers,
                                values.map { it.toFloatOrNull() },
                            )
                            val resourceEdits = sandboxChangedUnitResourceEdits(
                                resourceFields,
                                resourceValues.map { it.toDoubleOrNull() },
                            )
                            target?.let { onApply(it.unitIds, unitEdits to resourceEdits) }
                        },
                        enabled = canApply,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal fun sandboxNumericInputAllowed(value: String): Boolean =
    value.all { it.isDigit() || it == '.' || it == '-' || it == '+' || it == 'e' || it == 'E' }

/** Integral payloads travel through a native float command, so reject text that would round. */
internal fun sandboxNumericTextAccepts(field: SandboxUnitDataField, text: String): Boolean {
    val doubleValue = text.toDoubleOrNull() ?: return false
    val floatValue = text.toFloatOrNull() ?: return false
    return field.accepts(floatValue) && (!field.integral || doubleValue == floatValue.toDouble())
}

@Composable
internal fun SandboxForceBuildToggle() {
    val settings = remember { appKoin.get<Settings>() }
    var enabled by remember { mutableStateOf(settings.sandboxForceBuild) }
    val localSandbox = sandboxUnitDataUiAllowed(GameEngine.getInstance())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (localSandbox) {
            AeroButton(
                text = readI18n("rwpp.sandbox.editor.openTerrainEditor"),
                onClick = ::submitSandboxOpenTerrainEditor,
                accentColor = Color(0xFF62C8F2),
                subText = "MAP EDITOR",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                readI18n("rwpp.sandbox.editor.openTerrainEditorHelp"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
        AeroButton(
            text = readI18n("rwpp.sandbox.editor.forceBuild"),
            onClick = {
                enabled = !enabled
                settings.sandboxForceBuild = enabled
            },
            accentColor = if (enabled) Color(0xFF52D69B) else Color(0xFF7A8A9A),
            subText = if (enabled) "ON" else "OFF",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SandboxAttachmentDialog(
    visible: Boolean,
    options: SandboxAttachmentOptionsSnapshot?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var useUnitType by remember(options) { mutableStateOf(false) }
    var hostId by remember(options) { mutableStateOf(options?.hosts?.firstOrNull()?.id) }
    val host = options?.hosts?.firstOrNull { it.id == hostId } ?: options?.hosts?.firstOrNull()
    var slotIndex by remember(host?.id) { mutableIntStateOf(host?.slots?.firstOrNull()?.index ?: -1) }
    val slot = host?.slots?.firstOrNull { it.index == slotIndex } ?: host?.slots?.firstOrNull()
    var childId by remember(options) { mutableStateOf(options?.unattachedUnits?.firstOrNull()?.id) }
    val child = options?.unattachedUnits?.firstOrNull { it.id == childId }
        ?: options?.unattachedUnits?.firstOrNull()
    var typeName by remember(options) { mutableStateOf(options?.unitTypes?.firstOrNull()?.internalName) }
    val unitType = options?.unitTypes?.firstOrNull { it.internalName == typeName }
        ?: options?.unitTypes?.firstOrNull()
    var detachId by remember(options) { mutableStateOf(options?.attachedUnits?.firstOrNull()?.id) }
    val attached = options?.attachedUnits?.firstOrNull { it.id == detachId }
        ?: options?.attachedUnits?.firstOrNull()

    AnimatedAlertDialog(visible = visible, onDismissRequest = onDismiss) { dismiss ->
        AeroPanel(Modifier.fillMaxWidth(0.96f).heightIn(max = 620.dp).padding(6.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AeroHeader("UNIT ATTACHMENT", tag = "SANDBOX")
                if (options == null) {
                    Text(
                        readI18n("rwpp.sandbox.editor.loadingAttachments"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    if (host == null) {
                        Text(
                            readI18n("rwpp.sandbox.editor.noAttachmentHosts"),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        AeroDropdownMenu(
                            label = readI18n("rwpp.sandbox.editor.attachmentHost"),
                            items = options.hosts.map { it.label },
                            selectedIndex = options.hosts.indexOfFirst { it.id == host.id },
                            onItemSelected = { hostId = options.hosts.getOrNull(it)?.id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (host.slots.isNotEmpty()) {
                            AeroDropdownMenu(
                                label = readI18n("rwpp.sandbox.editor.attachmentSlot"),
                                items = host.slots.map { slotOption ->
                                    val state = slotOption.occupiedUnitName
                                        ?: readI18n("rwpp.sandbox.editor.emptySlot")
                                    "${slotOption.name} · $state"
                                },
                                selectedIndex = host.slots.indexOfFirst { it.index == slot?.index },
                                onItemSelected = {
                                    host.slots.getOrNull(it)?.let { selected -> slotIndex = selected.index }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        AeroButton(
                            readI18n("rwpp.sandbox.editor.existingUnit"),
                            onClick = { useUnitType = false },
                            accentColor = if (!useUnitType) Color(0xFF62C8F2) else Color(0xFF7A8A9A),
                            modifier = Modifier.weight(1f),
                        )
                        AeroButton(
                            readI18n("rwpp.sandbox.editor.unitType"),
                            onClick = { useUnitType = true },
                            accentColor = if (useUnitType) Color(0xFF62C8F2) else Color(0xFF7A8A9A),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (useUnitType) {
                        if (options.unitTypes.isNotEmpty()) AeroDropdownMenu(
                            label = readI18n("rwpp.sandbox.editor.attachmentUnitType"),
                            items = options.unitTypes.map { it.label },
                            selectedIndex = options.unitTypes.indexOfFirst { it.internalName == unitType?.internalName },
                            onItemSelected = { typeName = options.unitTypes.getOrNull(it)?.internalName },
                            modifier = Modifier.fillMaxWidth(),
                        ) else Text(
                            readI18n("rwpp.sandbox.editor.noAttachmentTypes"),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else if (options.unattachedUnits.isNotEmpty()) {
                        AeroDropdownMenu(
                            label = readI18n("rwpp.sandbox.editor.existingUnit"),
                            items = options.unattachedUnits.map { it.label },
                            selectedIndex = options.unattachedUnits.indexOfFirst { it.id == child?.id },
                            onItemSelected = { childId = options.unattachedUnits.getOrNull(it)?.id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Text(
                        readI18n("rwpp.sandbox.editor.noUnattachedUnits"),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val occupied = slot?.occupiedUnitId != null
                    AeroButton(
                        text = readI18n("rwpp.sandbox.editor.installAttachment"),
                        onClick = {
                            val selectedHost = host ?: return@AeroButton
                            val selectedSlot = slot ?: return@AeroButton
                            if (useUnitType) unitType?.let {
                                submitSandboxAttachUnitType(selectedHost.id, selectedSlot.index, it.internalName)
                            } else child?.let {
                                submitSandboxAttachExisting(selectedHost.id, selectedSlot.index, it.id)
                            }
                            dismiss()
                        },
                        enabled = host != null && slot != null && !occupied &&
                                (if (useUnitType) unitType != null else child != null),
                        accentColor = Color(0xFF52D69B),
                        subText = "EMPTY NATIVE SLOT",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (occupied) AeroButton(
                        text = readI18n("rwpp.sandbox.editor.detachFromSlot"),
                        onClick = { host?.let { submitSandboxDetachSlot(it.id, slot.index) } },
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (options.attachedUnits.isNotEmpty()) {
                        AeroHeader(readI18n("rwpp.sandbox.editor.attachedUnits"), tag = "DETACH")
                        AeroDropdownMenu(
                            label = readI18n("rwpp.sandbox.editor.attachedUnit"),
                            items = options.attachedUnits.map { it.label },
                            selectedIndex = options.attachedUnits.indexOfFirst { it.id == attached?.id },
                            onItemSelected = { detachId = options.attachedUnits.getOrNull(it)?.id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AeroButton(
                            text = readI18n("rwpp.sandbox.editor.detachUnit"),
                            onClick = { attached?.let { submitSandboxDetachUnit(it.id) } },
                            enabled = attached != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AeroButton(
                        readI18n("rwpp.common.cancel"),
                        onClick = dismiss,
                        modifier = Modifier.weight(1f),
                    )
                    AeroButton(
                        readI18n("rwpp.sandbox.editor.refresh"),
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

