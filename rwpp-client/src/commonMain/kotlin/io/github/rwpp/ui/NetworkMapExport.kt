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
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.map.exportCurrentNetworkMap
import io.github.rwpp.game.map.validExportMapName
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun NetworkMapExportDialog(game: Game, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("RWPP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AnimatedDialog(onDismissRequest = { if (!busy) onDismiss() }, enableDismiss = !busy) { dismiss ->
        AeroPanel(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AeroHeader(title = "EXPORT MAP", tag = "NETWORK")
                val engine = GameEngine.getInstance()
                val description = if (engine.networkEngine.sandboxMode || engine.debugEditorMode)
                    "rwpp.mapExport.sandboxDescription" else "rwpp.mapExport.description"
                Text(readI18n(description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                AeroTextField(
                    name, { name = it; status = "" }, readI18n("rwpp.mapExport.name"),
                    modifier = Modifier.fillMaxWidth(), enabled = !busy
                )
                if (!validExportMapName(name)) Text(
                    readI18n("rwpp.mapExport.invalidName"),
                    color = MaterialTheme.colorScheme.error
                )
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)
                AeroButton(readI18n("rwpp.mapExport.action"), onClick = {
                    if (!busy && validExportMapName(name)) {
                        busy = true
                        GameScope.launch {
                            runCatching {
                                val path = exportCurrentNetworkMap(GameEngine.getInstance(), name)
                                game.invalidateMapCatalog()
                                path
                            }.onSuccess { status = readI18n("rwpp.mapExport.success", it) }
                                .onFailure { status = readI18n("rwpp.mapExport.failure", it.message.orEmpty()) }
                            busy = false
                        }
                    }
                }, enabled = !busy && validExportMapName(name), modifier = Modifier.fillMaxWidth())
                AeroButton(
                    readI18n("rwpp.common.close"),
                    onClick = dismiss,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
