/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.logic.hud

import io.github.rwpp.config.HudComponentData
import io.github.rwpp.config.HudComponentDataList

/** Upgrade the previously empty layout once; preserve custom layouts and later explicit native-HUD choices. */
internal fun initializeDefaultHud(layout: HudComponentDataList) {
    if (!layout.defaultsInitialized && layout.hudComponentDataList.isEmpty()) {
        layout.hudComponentDataList.addAll(
            listOf(
                HudComponentData("hud_console_vertical", 0f, 0f, relativeX = 1f, relativeY = 0f),
                HudComponentData("telemetry", 0f, 0f, relativeX = 0.06f, relativeY = 0.01f),
                HudComponentData("chat", 0f, 0f, relativeX = 0.02f, relativeY = 0.28f),
                HudComponentData("events", 0f, 0f, relativeX = 0.5f, relativeY = 0.10f),
            )
        )
    }
    layout.defaultsInitialized = true
    if (!layout.selectionPanelsInitialized && layout.hudComponentDataList.any { it.id == "hud_console_vertical" }) {
        for ((id, x) in listOf("quick_selection" to 0.02f, "unit_groups" to 0.48f)) {
            if (layout.hudComponentDataList.none { it.id == id }) {
                layout.hudComponentDataList.add(HudComponentData(id, 0f, 0f, relativeX = x, relativeY = 0.98f))
            }
        }
    }
    layout.selectionPanelsInitialized = true
    if (!layout.observerStatisticsInitialized && layout.hudComponentDataList.any { it.id == "hud_console_vertical" }) {
        if (layout.hudComponentDataList.none { it.id == "observer_statistics" }) {
            layout.hudComponentDataList.add(
                HudComponentData(
                    "observer_statistics",
                    0f,
                    0f,
                    relativeX = 0.70f,
                    relativeY = 0.16f
                )
            )
        }
    }
    layout.observerStatisticsInitialized = true
}
