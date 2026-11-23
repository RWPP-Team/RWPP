/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.mod.Mod
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.appKoin
import io.github.rwpp.game.applyModSelection
import io.github.rwpp.game.importRwmod
import io.github.rwpp.game.scanMods
import io.github.rwpp.game.ModMemoryLoadFailure
import io.github.rwpp.generated.Res
import io.github.rwpp.generated.error_missingmap
import io.github.rwpp.screen.LocalScreenNavigator
import io.github.rwpp.screen.Screen
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.*
import io.github.rwpp.widget.v2.scrollbar.LazyColumnScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.koin.core.annotation.Single

private class ModCardInfo(val mod: Mod) {
    val title = mod.getTitle() ?: mod.name.orEmpty()
    val memoryUsage = mod.getMemoryUsageText().orEmpty()
    val error = mod.errorMessage?.takeIf { it.isNotBlank() }
    val details = listOfNotNull(mod.description, mod.descriptionWithInfo, mod.statusMessage)
        .filter { it.isNotBlank() }.distinct()
    val warnings = mod.warnings.toArray().filterIsInstance<String>().filter { it.isNotBlank() }.distinct()
}

@Single
class ModsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalScreenNavigator.current
        val game = koinInject<Game>()
        val android = koinInject<AppContext>().isAndroid()
        val compact = android || LocalWindowManager.current == WindowManager.Small

        var mods by remember { mutableStateOf(emptyList<ModCardInfo>()) }
        var filter by remember { mutableStateOf("") }

        val scope = rememberCoroutineScope()
        var reloadActive by remember { mutableStateOf(false) }
        var updated by remember { mutableStateOf(false) }
        var enabledChanged by remember { mutableStateOf(false) }

        val filteredMods = remember(mods, filter, updated) {
            mods.filter { it.title.contains(filter, ignoreCase = true) }
        }

        val enabledMods = remember(enabledChanged, filteredMods, updated) {
            filteredMods.filter { it.mod.isEnabled }
        }

        val disabledMods = remember(enabledChanged, filteredMods, updated) {
            filteredMods.filter { !it.mod.isEnabled }
        }

        LaunchedEffect(Unit) {
            try {
                mods = scanMods().map(::ModCardInfo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UI.showWarning(failure.message ?: readI18n("rwpp.common.unknownError"))
            }
        }

        fun showMods(refreshed: List<Mod>) {
            mods = refreshed.map(::ModCardInfo)
            updated = !updated
        }

        fun launchModReload(message: String = readI18n("rwpp.mod.reloading"), action: suspend () -> Unit) {
            if (reloadActive) return
            reloadActive = true
            loadingMessage = message
            loadingOverlayKeepsContent = true
            val presentationGeneration = beginLoadingOverlayPresentation()
            isLoading = true
            scope.launch {
                try {
                    // Wait for the overlay's drawWithContent acknowledgement, then one later frame.
                    // This keeps the ModsScreen scope alive and ensures the loading view completed a
                    // Compose draw before the synchronous native reload is queued to the game thread.
                    awaitLoadingOverlayDrawn(presentationGeneration)
                    withFrameNanos { }
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is ModMemoryLoadFailure) {
                        game.invalidateMapCatalog()
                        showMods(scanMods())
                    }
                    UI.showWarning(failure.message ?: readI18n("rwpp.common.unknownError"))
                } finally {
                    loadingMessage = ""
                    isLoading = false
                    loadingOverlayKeepsContent = false
                    finishLoadingOverlayPresentation(presentationGeneration)
                    reloadActive = false
                }
            }
        }

        fun reload() = launchModReload {
            showMods(applyModSelection(reloadData = !GameEngine.getInstance().networkEngine.networked))
            game.invalidateMapCatalog()
        }

        val header: @Composable () -> Unit = {
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

        }
        val filters: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeroTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = readI18n("rwpp.mod.filter"),
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
                    text = readI18n("rwpp.mod.disableAll"),
                    modifier = Modifier.width(130.dp),
                    isDanger = true,
                    onClick = {
                        mods.forEach { it.mod.disabled = true }
                        enabledChanged = !enabledChanged
                    }
                )
            }

        }
        val actions: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier = modifier.fillMaxWidth(),
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
                        val context = appKoin.get<AppContext>()
                        context.openFileChooser { file ->
                            scope.launch {
                                launchModReload(readI18n("rwpp.mod.importing", file.name)) {
                                    try {
                                        showMods(importRwmod(file))
                                    } finally {
                                        withContext(Dispatchers.IO) { context.releasePickerFile(file) }
                                    }
                                }
                            }
                        }
                    }
                )

                AeroButton(
                    text = readI18n("rwpp.mod.apply"),
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        launchModReload {
                            applyModSelection(reloadData = !GameEngine.getInstance().networkEngine.networked)
                            game.invalidateMapCatalog()
                            navigator.pop()
                        }
                    }
                )
            }
        }

        fun toggle(mod: Mod, disabled: Boolean) {
            mod.disabled = disabled; enabledChanged = !enabledChanged
        }

        fun delete(mod: Mod) {
            if (mod.canBeDeleted() && mod.deleteMod()) {
                mods = mods.filterNot { it.mod === mod }
            } else UI.showWarning(readI18n("rwpp.mod.removeInfo"))
        }
        AeroScaledContainer {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (android) {
                        AeroPanel(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                            actions(Modifier.padding(8.dp))
                        }
                    }
                },
            ) { padding ->
                AeroPanel(Modifier.fillMaxSize().padding(padding).padding(if (compact) 6.dp else 10.dp)) {
                    ResponsiveSelectionBody(
                        compact = compact,
                        modifier = Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp),
                        compactContent = {
                            item(key = "mods-header") { header() }
                            item(key = "mods-filter") { filters() }
                            item(key = "mods-enabled") { AeroHeader(title = "ENABLED", tag = "${enabledMods.size} ITEMS") }
                            items(count = enabledMods.size, key = { "enabled-${enabledMods[it].mod.modId}" }) { index ->
                                ModCard(enabledMods[index], true, { toggle(it, true) }, ::delete)
                            }
                            item(key = "mods-disabled") {
                                AeroHeader(
                                    title = "DISABLED",
                                    tag = "${disabledMods.size} ITEMS"
                                )
                            }
                            items(count = disabledMods.size, key = { "disabled-${disabledMods[it].mod.modId}" }) { index ->
                                ModCard(disabledMods[index], false, { toggle(it, false) }, ::delete)
                            }
                            if (!android) item(key = "mods-actions") { actions(Modifier) }
                        },
                        desktopContent = {
                            Column(Modifier.fillMaxSize()) {
                                header()
                                Spacer(Modifier.height(8.dp))
                                filters()
                                AeroDivider(Modifier.padding(vertical = 8.dp))
                                ResponsiveTwoPane(
                                    compact = false, modifier = Modifier.weight(1f).fillMaxWidth(),
                                    first = {
                                        ModListColumn(
                                            "ENABLED",
                                            enabledMods,
                                            true,
                                            Modifier.fillMaxSize(),
                                            onToggle = { toggle(it, true) },
                                            onDelete = ::delete
                                        )
                                    },
                                    second = {
                                        ModListColumn(
                                            "DISABLED",
                                            disabledMods,
                                            false,
                                            Modifier.fillMaxSize(),
                                            onToggle = { toggle(it, false) },
                                            onDelete = ::delete
                                        )
                                    })
                                AeroDivider(Modifier.padding(vertical = 8.dp))
                                actions(Modifier)
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun ModCard(info: ModCardInfo, isEnabledList: Boolean, onToggle: (Mod) -> Unit, onDelete: (Mod) -> Unit) {
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
                                text = info.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = readI18n("rwpp.mod.memoryUsage", info.memoryUsage),
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
                            contentDescription = readI18n(if (isEnabledList) "rwpp.mod.moveToDisabled" else "rwpp.mod.moveToEnabled"),
                            onClick = { onToggle(info.mod) }
                        )

                        AeroIconButton(
                            painter = rememberVectorPainter(Icons.Default.Delete),
                            size = 32.dp,
                            iconSize = 16.dp,
                            contentDescription = readI18n("rwpp.mod.delete"),
                            isDanger = true,
                            onClick = { onDelete(info.mod) }
                        )
                    }
                }

                info.error?.let { error ->
                    Text(
                        text = error,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                val warningText = info.warnings.takeIf { it.isNotEmpty() }?.let {
                    readI18n("rwpp.mod.warnings", it.size) + "\n" + it.joinToString("\n")
                }
                val expandedDescription = (info.details + listOfNotNull(warningText)).joinToString("\n\n")
                if (expandedDescription.isNotEmpty()) {
                    val expandedTextStyle = remember {
                        SpanStyle(
                            fontWeight = FontWeight.W500,
                            color = Color(173, 216, 230),
                            fontStyle = FontStyle.Italic,
                            textDecoration = TextDecoration.Underline
                        )
                    }
                    SelectionContainer {
                        ExpandableText(
                            text = expandedDescription,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            textModifier = Modifier.padding(top = 4.dp),
                            showMoreText = readI18n("rwpp.common.showMore"),
                            showMoreStyle = expandedTextStyle,
                            showLessText = readI18n("rwpp.common.showLess"),
                            showLessStyle = expandedTextStyle,
                            onExpandToggle = { expanded ->
                                Sounds.playSound(if (expanded) Sounds.openPopup else Sounds.closePopup)
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
        mods: List<ModCardInfo>,
        isEnabledList: Boolean,
        modifier: Modifier = Modifier,
        onToggle: (Mod) -> Unit,
        onDelete: (Mod) -> Unit
    ) {
        Column(modifier = modifier) {
            AeroHeader(
                title = title,
                tag = "${mods.size} ITEMS",
                color = if (isEnabledList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(6.dp))

            val card: @Composable (Int) -> Unit = { index -> ModCard(mods[index], isEnabledList, onToggle, onDelete) }

            val state = rememberLazyListState()
            LazyColumnScrollbar(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(count = mods.size, key = { mods[it].mod.modId }) { card(it) }
                }
            }
        }
    }

}
