/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.building.BuildingPlanningController
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroPanel

/** Persistent feedback and a pointer-accessible alternative to the planner shortcuts. */
@Composable
internal fun GameScreen.BuildingPlanningControls() {
    var state by remember { mutableStateOf(BuildingPlanningController.snapshot) }
    OnGameUpdate { state = BuildingPlanningController.snapshot }
    AeroPanel {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AeroButton(
                text = readI18n("rwpp.planning.toggle"),
                subText = if (state.enabled) "ON · ALT+B" else "OFF · ALT+B",
                onClick = BuildingPlanningController::toggleMode,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.enabled) {
                Text(
                    readI18n("rwpp.planning.mode.${state.mode.name}"), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AeroButton(
                        text = readI18n("rwpp.planning.selectBlueprintShort"),
                        onClick = BuildingPlanningController::beginBlueprintSelection, modifier = Modifier.weight(1f)
                    )
                    AeroButton(
                        text = readI18n("rwpp.planning.reclaimShort"),
                        onClick = BuildingPlanningController::beginReclaimSelection, modifier = Modifier.weight(1f)
                    )
                }
                state.statusKey?.let { key ->
                    Text(
                        readI18n(key, state.lastActionCount, state.blueprint.size), fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
