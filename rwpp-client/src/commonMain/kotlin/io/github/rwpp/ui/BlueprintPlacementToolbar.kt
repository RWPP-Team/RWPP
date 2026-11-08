package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import io.github.rwpp.widget.v2.AeroIconButton

/** Compact planning actions; the map remains visible around the four icon targets. */
@Composable
internal fun GameScreen.BlueprintPlacementToolbar(visible: Boolean) {
    if (!visible) return
    var state by remember { mutableStateOf(BuildingPlanningController.snapshot) }
    OnGameUpdate {
        val next = BuildingPlanningController.snapshot
        if (state != next) state = next
    }
    val selecting = state.mode == BuildingPlanningMode.SELECT_BLUEPRINT
    val placing = state.mode == BuildingPlanningMode.PLACE_BLUEPRINT
    if (!selecting && !placing) return
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AeroIconButton(rememberVectorPainter(Icons.Default.Close), BuildingPlanningController::cancelBlueprint,
                contentDescription = readI18n("rwpp.common.cancel"), size = 44.dp, isDanger = true)
            if (placing) {
                AeroIconButton(rememberVectorPainter(Icons.Default.Refresh), BuildingPlanningController::beginBlueprintSelection,
                    contentDescription = readI18n("rwpp.planning.independentReselect"), size = 44.dp)
                AeroIconButton(rememberVectorPainter(Icons.Default.Build), BuildingPlanningController::matchBlueprintBuilders,
                    contentDescription = readI18n("rwpp.planning.independentBuilders"), size = 44.dp)
                AeroIconButton(rememberVectorPainter(Icons.Default.Check),
                    { if (state.canConfirm) BuildingPlanningController.confirmBlueprint() },
                    modifier = Modifier.alpha(if (state.canConfirm) 1f else 0.4f)
                        .semantics { if (!state.canConfirm) disabled() },
                    contentDescription = readI18n("rwpp.common.confirm"), size = 44.dp,
                    accentColor = if (state.canConfirm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
