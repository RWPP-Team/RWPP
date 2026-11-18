/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hud.AdvancedRally
import io.github.rwpp.game.hud.MAX_RALLY_POINTS
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedRallyControls() {
    val draft = AdvancedRally.draft ?: return
    val hasPoints = draft.points.isNotEmpty()
    fun edit(action: AdvancedRally.Edit) {
        GameScope.launch {
            GameEngine.getInstance()?.let { AdvancedRally.edit(it, draft.editor, action) }
        }
    }

    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
        AeroPanel(Modifier.widthIn(max = 440.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    readI18n("rwpp.hud.rally.instructions", draft.points.size, MAX_RALLY_POINTS),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AeroButton(readI18n("rwpp.hud.rally.undo"), { edit(AdvancedRally.Edit.UNDO) }, enabled = hasPoints)
                    AeroButton(readI18n("rwpp.common.reset"), { edit(AdvancedRally.Edit.RESET) }, enabled = hasPoints)
                    AeroButton(readI18n("rwpp.common.cancel"), { edit(AdvancedRally.Edit.CANCEL) })
                    AeroButton(readI18n("rwpp.common.save"), { edit(AdvancedRally.Edit.SAVE) })
                }
            }
        }
    }
}
