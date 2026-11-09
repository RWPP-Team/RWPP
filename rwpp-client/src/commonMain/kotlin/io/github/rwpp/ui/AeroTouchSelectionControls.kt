/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.game.building.BuildingPlanningController
import io.github.rwpp.game.hotkeys.NativeGameHotkeys
import io.github.rwpp.game.hotkeys.NativeTacticalActions
import io.github.rwpp.game.hotkeys.SelectionCameraFollowController
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.AeroIconButton
import io.github.rwpp.widget.v2.AeroPanel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Compact touch controls; the native action implementations are shared with keyboard hotkeys. */
@Composable
internal fun GameScreen.AeroTouchSelectionControls() {
    if (!koinInject<AppContext>().isAndroid()) return
    val settings = koinInject<Settings>()
    var followStatus by remember { mutableStateOf(SelectionCameraFollowController.status) }
    // Keep only the two values this panel renders. Copying the whole planner snapshot each frame
    // rebuilt the row continuously, even while nothing visible had changed.
    var planningEnabled by remember { mutableStateOf(BuildingPlanningController.snapshot.enabled) }
    var planningMode by remember { mutableStateOf(BuildingPlanningController.snapshot.mode) }
    OnGameUpdate {
        followStatus = SelectionCameraFollowController.status
        val planning = BuildingPlanningController.snapshot
        if (planningEnabled != planning.enabled) planningEnabled = planning.enabled
        if (planningMode != planning.mode) planningMode = planning.mode
    }

    AeroPanel {
        FlowRow(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxItemsInEachRow = 4,
        ) {
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Person),
                label = readI18n("rwpp.hud.touchControls.followShort"),
                description = readI18n("rwpp.hud.touchControls.followSelection"),
                active = followStatus.active,
                onClick = SelectionCameraFollowController::toggle,
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Check),
                label = readI18n("rwpp.hud.touchControls.selectAllShort"),
                description = readI18n("rwpp.hud.touchControls.selectAll"),
                onClick = { GameScope.launch { NativeGameHotkeys.selectAll() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Refresh),
                label = readI18n("rwpp.hud.touchControls.sameTypeShort"),
                description = readI18n("rwpp.hud.touchControls.selectSameType"),
                onClick = { GameScope.launch { NativeGameHotkeys.selectSameType() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Settings),
                label = readI18n("rwpp.hud.touchControls.builderTypesShort"),
                description = readI18n("rwpp.hud.touchControls.builderTypes"),
                onClick = SmartSelectionExtraBuilderTypePickerController::open,
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Favorite),
                label = readI18n("rwpp.tactical.lowHealthShort"),
                description = readI18n("rwpp.tactical.lowHealth"),
                onClick = { GameScope.launch { NativeTacticalActions.selectLowHealth() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.ArrowDropDown),
                label = readI18n("rwpp.tactical.autoLoadShort"),
                description = readI18n("rwpp.tactical.autoLoad"),
                onClick = { GameScope.launch { NativeTacticalActions.autoLoad() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Build),
                label = readI18n("rwpp.planning.toggleShort"),
                description = readI18n("rwpp.planning.toggle"),
                active = planningEnabled,
                onClick = { GameScope.launch { BuildingPlanningController.toggleMode() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Create),
                label = readI18n("rwpp.planning.selectBlueprintShort"),
                description = readI18n("rwpp.planning.selectBlueprint"),
                onClick = { GameScope.launch { BuildingPlanningController.beginBlueprintSelection() } },
            )
            TouchSelectionButton(
                icon = rememberVectorPainter(Icons.Default.Delete),
                label = readI18n("rwpp.planning.reclaimShort"),
                description = readI18n("rwpp.planning.reclaim"),
                onClick = { GameScope.launch { BuildingPlanningController.beginReclaimSelection() } },
            )
        }
    }
    SmartSelectionExtraBuilderTypePickerHost(settings)
}

@Composable
private fun TouchSelectionButton(
    icon: Painter,
    label: String,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.width(48.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        AeroIconButton(
            painter = icon,
            onClick = onClick,
            contentDescription = description,
            size = 48.dp,
            iconSize = 22.dp,
            forceHover = active,
            accentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            clickSound = Sounds.select,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
