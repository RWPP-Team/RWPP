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
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameSetupMapType
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.map.GameMap
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

internal fun validSaveName(value: String): Boolean = value.isNotBlank() && value.length <= 80 &&
        value == value.trim() && value.none { it.isISOControl() || it in "\\/:*?\"<>|." }

@Composable
internal fun GameSaveDialog(load: Boolean, onDismiss: () -> Unit, onLoaded: () -> Unit) {
    val game = koinInject<Game>()
    var name by remember { mutableStateOf("RWPP_" + SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())) }
    val saves = remember { mutableStateListOf<GameMap>() }
    var savesLoading by remember { mutableStateOf(true) }
    var savesRefresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var overwrite by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(savesRefresh) {
        if (savesRefresh > 0) game.invalidateMapCatalog()
        saves.clear()
        savesLoading = true
        try {
            game.getMapPages(mapType = GameSetupMapType.savedGame).collect { page ->
                saves.addAll(page.items)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            status = failure.message ?: readI18n("rwpp.catalog.unknownError")
        } finally {
            savesLoading = false
        }
    }
    fun runOperation(operation: () -> Unit) {
        if (busy) return
        busy = true
        GameScope.launch {
            runCatching(operation).onFailure { status = it.message ?: readI18n("rwpp.save.failed") }
            busy = false
        }
    }
    AnimatedDialog(
        onDismissRequest = { if (loaded) onLoaded() else onDismiss() },
        enableDismiss = !busy
    ) { dismiss ->
        AeroPanel(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 580.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AeroHeader(
                    if (load) "LOAD SAVE" else "SAVE GAME",
                    tag = if (load) "LOAD" else "SAVE",
                )
                AeroDivider()
                if (!load) {
                    AeroTextField(name, { name = it; overwrite = false }, readI18n("rwpp.save.name"), enabled = !busy)
                    if (!validSaveName(name)) Text(readI18n("rwpp.save.invalidName"))
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (saves.isEmpty()) Text(
                        if (savesLoading) readI18n("rwpp.catalog.loading") else readI18n("rwpp.save.noneAvailable"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    saves.forEach { save ->
                        AeroButton(save.displayName(), onClick = {
                            if (!busy) {
                                if (!load) {
                                    name = save.mapName; overwrite = false
                                } else runOperation {
                                    check(!game.state.multiplayer) { readI18n("rwpp.save.multiplayerLoadUnsupported") }
                                    val engine = GameEngine.getInstance()
                                    engine.networkEngine.disconnect("loading save")
                                    check(
                                        engine.gameSaver.loadSaveFile(
                                            save.mapName + ".rwsave",
                                            false
                                        )
                                    ) { readI18n("rwpp.save.loadFailed") }
                                    game.continueGame()
                                    loaded = true
                                    dismiss()
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.primary)
                if (!load) AeroButton(
                    if (overwrite) readI18n("rwpp.save.confirmOverwrite") else readI18n("rwpp.common.save"),
                    onClick = {
                        if (validSaveName(name) && !busy) {
                            if (saves.any { it.mapName.equals(name, true) } && !overwrite) overwrite = true
                            else runOperation {
                                val engine = GameEngine.getInstance()
                                engine.gameSaver.autoSaveGame(name, false)
                                check(
                                    engine.gameSaver.getSaveFile(name + ".rwsave", false).length() > 0
                                ) { readI18n("rwpp.save.writeFailed") }
                                status = readI18n("rwpp.save.saved", name)
                                savesRefresh++
                                overwrite = false
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                )
                AeroButton(
                    if (busy) readI18n("rwpp.common.processing") else readI18n("rwpp.common.close"),
                    onClick = { if (!busy) dismiss() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
