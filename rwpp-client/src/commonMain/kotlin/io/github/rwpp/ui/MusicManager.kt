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
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.game.audio.MusicEntry
import io.github.rwpp.game.audio.NativeMusicLibrary
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.launch

@Composable
internal fun MusicPreferences(settings: Settings) {
    var open by remember { mutableStateOf(false) }
    AeroSettingToggle(readI18n("rwpp.music.playToEnd"), settings.playMusicToEnd, onCheckedChange = {
        settings.playMusicToEnd = it
    })
    AeroButton(readI18n("rwpp.music.open"), onClick = { open = true })
    if (open) MusicManagerDialog { open = false }
}

@Composable
private fun MusicManagerDialog(onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf<List<MusicEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        GameScope.launch {
            runCatching { NativeMusicLibrary.tracks(GameEngine.getInstance()) }
                .onSuccess { entries = it }
                .onFailure { error = it.message.orEmpty() }
        }
    }
    AnimatedDialog(onDismissRequest = onDismiss) { dismiss ->
        AeroPanel(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AeroHeader(title = "MUSIC LIBRARY", tag = "AUDIO")
                if (error != null) Text(
                    readI18n("rwpp.music.error", error.orEmpty()),
                    color = MaterialTheme.colorScheme.error
                )
                else if (entries.isEmpty()) Text(readI18n("rwpp.music.empty"))
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for ((path, title) in entries) {
                        AeroButton(title, onClick = {
                            GameScope.launch {
                                NativeMusicLibrary.request(GameEngine.getInstance().musicController, path)
                            }
                        }, modifier = Modifier.fillMaxWidth())
                        Text(
                            path,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                AeroButton(readI18n("rwpp.common.close"), onClick = dismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
