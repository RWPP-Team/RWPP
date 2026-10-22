/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.api.game.Game
import io.github.rwpp.game.reloadMods
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.error_missingmap
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.modDir
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single
import java.io.File

@Single
class ModsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()

        var deletedMod by remember { mutableStateOf(false) }
        val mods = remember {
            SnapshotStateList<Mod>().apply {
                addAll(GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>())
            }
        }
        var filter by remember { mutableStateOf("") }
        val filteredMods = remember(mods.size, filter) {
            mods.filter { (it.getTitle() ?: it.name.orEmpty()).contains(filter, ignoreCase = true) }
        }

        val scope = rememberCoroutineScope()
        var updated by remember { mutableStateOf(false) }
        var enabledChanged by remember { mutableStateOf(false) }

        val enabledMods = remember(enabledChanged, filteredMods.size, updated) {
            filteredMods.filter { it.isEnabled }
        }

        val disabledMods = remember(enabledChanged, filteredMods.size, updated) {
            filteredMods.filter { !it.isEnabled }
        }

        fun reload() {
            scope.launch(Dispatchers.IO) {
                reloadMods()
                game.getAllMaps(true)
                mods.clear()
                mods.addAll(GameEngine.getInstance().modManager.refreshModList().filterIsInstance<Mod>())
                updated = !updated
            }
        }

        AeroScaledContainer {
            AeroPanel(
                modifier = Modifier.fillMaxSize().padding(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroHeader(
                            title = "MODIFICATIONS MATRIX",
                            tag = "TOTAL: ${mods.size}",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        AeroButton(
                            text = readI18n("rwpp.common.exit"),
                            isDanger = true,
                            onClick = { navigator.pop() },
                            clickSound = Sounds.closePopup,
                            modifier = Modifier.width(100.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AeroTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = "FILTER_MODS",
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )

                        AeroButton(
                            text = "DISABLE ALL",
                            modifier = Modifier.width(130.dp),
                            isDanger = true,
                            onClick = {
                                mods.forEach { it.disabled = true }
                                enabledChanged = !enabledChanged
                            }
                        )
                    }

                    AeroDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ModListColumn(
                            title = readI18n("rwpp.mod.enabled"),
                            mods = enabledMods,
                            isEnabledList = true,
                            modifier = Modifier.weight(1f),
                            onToggle = { mod ->
                                mod.disabled = true
                                enabledChanged = !enabledChanged
                            },
                            onDelete = { mod ->
                                if (mod.canBeDeleted() && mod.deleteMod()) {
                                    mods.remove(mod)
                                    deletedMod = true
                                } else {
                                    UI.showWarning(readI18n("rwpp.mod.removeInfo"))
                                }
                            }
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        ModListColumn(
                            title = readI18n("rwpp.mod.disabled"),
                            mods = disabledMods,
                            isEnabledList = false,
                            modifier = Modifier.weight(1f),
                            onToggle = { mod ->
                                mod.disabled = false
                                enabledChanged = !enabledChanged
                            },
                            onDelete = { mod ->
                                if (mod.canBeDeleted() && mod.deleteMod()) {
                                    mods.remove(mod)
                                    deletedMod = true
                                } else {
                                    UI.showWarning(readI18n("rwpp.mod.removeInfo"))
                                }
                            }
                        )
                    }

                    AeroDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AeroButton(
                            text = readI18n("rwpp.mod.reload"),
                            modifier = Modifier.weight(1f),
                            onClick = { reload() }
                        )

                        AeroButton(
                            text = readI18n("rwpp.mod.inputFile"),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                appKoin.get<AppContext>().openFileChooser { file ->
                                    runCatching {
                                        if (file.extension == "rwmod") {
                                            file.copyTo(File("$modDir/${file.name}"))
                                            reload()
                                        } else {
                                            UI.showWarning(readI18n("rwpp.mod.loadInfo"))
                                        }
                                    }.onFailure {
                                        UI.showWarning(it.message ?: "Unknown error")
                                    }
                                }
                            }
                        )

                        AeroButton(
                            text = readI18n("rwpp.mod.apply"),
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                runBlocking {
                                    GameScope.launch {
                                        val engine = GameEngine.getInstance()
                                        engine.modManager.saveSelection()
                                        engine.settings.save()
                                        if (!engine.networkEngine.networked) {
                                            engine.modManager.reloadModsAndSelection()
                                            engine.modManager.reloadModData()
                                        }
                                    }.join()
                                }
                                navigator.pop()
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ModListColumn(
        title: String,
        mods: List<Mod>,
        isEnabledList: Boolean,
        modifier: Modifier = Modifier,
        onToggle: (Mod) -> Unit,
        onDelete: (Mod) -> Unit
    ) {
        Column(modifier = modifier.fillMaxHeight()) {
            AeroHeader(
                title = title,
                tag = "${mods.size} ITEMS",
                color = if (isEnabledList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(6.dp))

            val state = rememberLazyListState()
            LazyColumnScrollbar(
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        count = mods.size,
                        key = { mods[it].modId }
                    ) { index ->
                        val mod = mods[index]
                        AeroPanel(
                            borderColor = if (isEnabledList) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Image(
                                            painter = painterResource(Res.drawable.error_missingmap),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp))
                                        )

                                        Column {
                                            Text(
                                                text = mod.getTitle() ?: mod.name.orEmpty(),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "RAM: ${mod.getMemoryUsageText()}",
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color.Green
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        AeroIconButton(
                                            painter = rememberVectorPainter(
                                                if (isEnabledList) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack
                                            ),
                                            size = 32.dp,
                                            iconSize = 16.dp,
                                            onClick = { onToggle(mod) }
                                        )

                                        AeroIconButton(
                                            painter = rememberVectorPainter(Icons.Default.Delete),
                                            size = 32.dp,
                                            iconSize = 16.dp,
                                            isDanger = true,
                                            onClick = { onDelete(mod) }
                                        )
                                    }
                                }

                                if (mod.errorMessage != null) {
                                    Text(
                                        text = mod.errorMessage!!,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                if (!mod.description.isNullOrBlank()) {
                                    Text(
                                        text = mod.description.orEmpty(),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
