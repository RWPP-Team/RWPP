/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.v2.AeroSettingDropDown
import io.github.rwpp.widget.v2.AeroSettingGroup

private val mouseButtonOptions = listOf(
    "rwpp.mouse.native",
    "rwpp.mouse.left",
    "rwpp.mouse.right",
)

private val actionConfirmOptions = listOf(
    "attackMove" to "rwpp.mouse.action.attackMove",
    "guardUnit" to "rwpp.mouse.action.guardUnit",
    "patrol" to "rwpp.mouse.action.patrol",
    "reclaimTarget" to "rwpp.mouse.action.reclaimTarget",
    "repairTarget" to "rwpp.mouse.action.repairTarget",
    "setRally" to "rwpp.mouse.action.setRally",
    "targetGround" to "rwpp.mouse.action.targetGround",
)

@Composable
internal fun MouseControlPreferences(settings: Settings) {
    AeroSettingGroup(
        title = "MOUSE CONTROLS",
        tag = "INPUT",
    ) {
        Text(readI18n("rwpp.mouse.help"), modifier = Modifier.fillMaxWidth())
        AeroSettingDropDown(
            label = readI18n("rwpp.mouse.orderButton"),
            items = mouseButtonOptions.map { readI18n(it) },
            selectedIndex = settings.mouseOrderButton.coerceIn(0, 2),
            onItemSelected = { index, _ -> settings.mouseOrderButton = index },
        )
        AeroSettingDropDown(
            label = readI18n("rwpp.mouse.buildConfirmButton"),
            items = mouseButtonOptions.map { readI18n(it) },
            selectedIndex = settings.mouseBuildConfirmButton.coerceIn(0, 2),
            onItemSelected = { index, _ -> settings.mouseBuildConfirmButton = index },
        )
        actionConfirmOptions.forEach { (action, labelKey) ->
            AeroSettingDropDown(
                label = readI18n(labelKey),
                items = mouseButtonOptions.map { readI18n(it) },
                selectedIndex = settings.actionConfirmButtons[action]?.coerceIn(0, 2) ?: 0,
                onItemSelected = { index, _ ->
                    settings.actionConfirmButtons = if (index == 0) {
                        settings.actionConfirmButtons - action
                    } else {
                        settings.actionConfirmButtons + (action to index)
                    }
                },
            )
        }
    }
}
