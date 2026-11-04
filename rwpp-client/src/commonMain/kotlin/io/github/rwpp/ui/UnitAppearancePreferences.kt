/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.graphics.parseUnitAppearanceColorSource
import io.github.rwpp.widget.v2.AeroSettingGroup
import io.github.rwpp.widget.v2.AeroSettingTextField
import io.github.rwpp.widget.v2.AeroSettingToggle

@Composable
fun UnitAppearancePreferences(settings: Settings) {
    AeroSettingGroup(title = "UNIT PAINT", tag = "LOCAL DISPLAY") {
        Text(
            readI18n("rwpp.appearance.paint.help"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        AeroSettingToggle(
            label = readI18n("rwpp.appearance.paint.enabled"),
            defaultValue = settings.unitAppearancePaintEnabled,
            subLabel = readI18n("rwpp.appearance.paint.enabledHelp"),
            onCheckedChange = { settings.unitAppearancePaintEnabled = it },
        )
        Text(
            readI18n("rwpp.appearance.paint.sourceHelp"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        var playerEntries by remember(settings.unitAppearancePlayerColorMap) {
            mutableStateOf(parsePlayerColorEntries(settings.unitAppearancePlayerColorMap))
        }
        Text(
            readI18n("rwpp.appearance.paint.playerMap"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            playerEntries.forEachIndexed { index, entry ->
                AppearanceColorField(
                    key = "rwpp.appearance.paint.position",
                    currentValue = entry,
                    formatPosition = index + 1,
                ) { value ->
                    playerEntries = playerEntries.toMutableList().also { it[index] = value }
                    settings.unitAppearancePlayerColorMap = playerEntries.joinToString(",")
                }
            }
        }
        AeroSettingToggle(
            label = readI18n("rwpp.appearance.paint.triColor"),
            defaultValue = settings.unitAppearanceTriColorEnabled,
            subLabel = readI18n("rwpp.appearance.paint.triColorHelp"),
            onCheckedChange = { settings.unitAppearanceTriColorEnabled = it },
        )
        AppearanceColorField("rwpp.appearance.paint.self", settings.unitAppearanceSelfColor) {
            settings.unitAppearanceSelfColor = it
        }
        AppearanceColorField("rwpp.appearance.paint.enemy", settings.unitAppearanceEnemyColor) {
            settings.unitAppearanceEnemyColor = it
        }
        AppearanceColorField("rwpp.appearance.paint.ally", settings.unitAppearanceAllyColor) {
            settings.unitAppearanceAllyColor = it
        }
    }

    AeroSettingGroup(title = "SELECTION RINGS", tag = "RELATION COLORS") {
        Text(
            readI18n("rwpp.appearance.ring.help"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        AeroSettingToggle(
            label = readI18n("rwpp.appearance.ring.enabled"),
            defaultValue = settings.unitAppearanceSelectionRingsEnabled,
            subLabel = readI18n("rwpp.appearance.ring.enabledHelp"),
            onCheckedChange = { settings.unitAppearanceSelectionRingsEnabled = it },
        )
        AppearanceColorField("rwpp.appearance.ring.self", settings.unitAppearanceRingSelfColor, hexOnly = true) {
            settings.unitAppearanceRingSelfColor = it
        }
        AppearanceColorField("rwpp.appearance.ring.enemy", settings.unitAppearanceRingEnemyColor, hexOnly = true) {
            settings.unitAppearanceRingEnemyColor = it
        }
        AppearanceColorField("rwpp.appearance.ring.ally", settings.unitAppearanceRingAllyColor, hexOnly = true) {
            settings.unitAppearanceRingAllyColor = it
        }
        AppearanceColorField("rwpp.appearance.ring.neutral", settings.unitAppearanceRingNeutralColor, hexOnly = true) {
            settings.unitAppearanceRingNeutralColor = it
        }
    }

    AeroSettingGroup(title = "UNIT OUTLINE", tag = "PAINT MATCH") {
        Text(
            readI18n("rwpp.appearance.outline.help"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        AeroSettingToggle(
            label = readI18n("rwpp.appearance.outline.enabled"),
            defaultValue = settings.unitAppearanceOutlineBrighteningEnabled,
            subLabel = readI18n("rwpp.appearance.outline.enabledHelp"),
            onCheckedChange = { settings.unitAppearanceOutlineBrighteningEnabled = it },
        )
    }
}

@Composable
private fun AppearanceColorField(
    key: String,
    currentValue: String,
    formatPosition: Int? = null,
    hexOnly: Boolean = false,
    onChange: (String) -> Unit,
) {
    var value by remember(key, formatPosition, currentValue) { mutableStateOf(currentValue) }
    AeroSettingTextField(
        label = if (formatPosition == null) readI18n(key) else readI18n(key, formatPosition),
        value = value,
        modifier = Modifier.fillMaxWidth(),
        onValueChange = {
            value = it
            onChange(it)
        },
    )
    val valid = if (hexOnly) {
        parseUnitAppearanceColorSource(value) is io.github.rwpp.graphics.UnitAppearanceColorSource.Fixed
    } else {
        parseUnitAppearanceColorSource(value) != null
    }
    if (!valid) {
        Text(
            readI18n(if (hexOnly) "rwpp.appearance.ring.invalid" else "rwpp.appearance.source.invalid"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun parsePlayerColorEntries(value: String): List<String> {
    val entries = value.split(',')
    return List(10) { index -> entries.getOrNull(index)?.trim() ?: (index + 1).toString() }
}
