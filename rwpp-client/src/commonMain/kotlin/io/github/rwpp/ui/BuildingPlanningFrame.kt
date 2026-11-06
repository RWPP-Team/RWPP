/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.building.BuildingPlanningController
import io.github.rwpp.game.building.BuildingPlanningMode

private val BLUEPRINT_FRAME = Color(0xff3ccdff)
private val RECLAIM_FRAME = Color(0xffff6a3d)

/**
 * A single screen-edge frame replaces the former planner panel: blue while a blueprint is
 * armed, red while a reclaim area is being selected. It takes no input.
 */
@Composable
internal fun GameScreen.BuildingPlanningFrame(visible: Boolean) {
    if (!visible) return
    var state by remember { mutableStateOf(BuildingPlanningController.snapshot) }
    OnGameUpdate { state = BuildingPlanningController.snapshot }
    val mode = state.mode
    if (mode == BuildingPlanningMode.OFF) return
    val color = if (mode == BuildingPlanningMode.RECLAIM_AREA) RECLAIM_FRAME else BLUEPRINT_FRAME
    Box(Modifier.fillMaxSize().border(3.dp, color.copy(alpha = 0.85f)))
}
