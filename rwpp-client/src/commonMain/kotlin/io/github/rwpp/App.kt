/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

@file:Suppress("DuplicatedCode")

package io.github.rwpp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import coil3.size.Precision
import io.github.rwpp.coil.ImageableFetcherFactory
import io.github.rwpp.coil.ImageableKeyer
import io.github.rwpp.core.Logic
import io.github.rwpp.api.event.GlobalEventChannel
import io.github.rwpp.event.OnDispose
import io.github.rwpp.api.event.events.ReloadModEvent
import io.github.rwpp.api.event.events.ReloadModFinishedEvent
import io.github.rwpp.api.event.events.StateChangedEvent
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.State
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.screen.Screen
import io.github.rwpp.screen.ScreenNavigator
import io.github.rwpp.sound.Sounds
import io.github.rwpp.ui.*
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.delay
import org.koin.compose.currentKoinScope
import kotlin.time.Duration.Companion.milliseconds

var LocalWindowManager = staticCompositionLocalOf { WindowManager.Large }

@Suppress("UnusedBoxWithConstraintsScope", "UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    LaunchedEffect(Unit) {
        Logic.checkUpdate()
    }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(ImageableFetcherFactory())
                add(ImageableKeyer())
            }
            .precision(Precision.EXACT)
            .build()
    }

    val scope = currentKoinScope()
    val navigator = remember(scope) {
        val initialScreen: Screen = if (appKoin.get<Game>().state.state == State.Playing) {
            scope.get<GameScreen>()
        } else {
            scope.get<MainMenuScreen>()
        }
        ScreenNavigator(initialScreen, scope)
    }
    LaunchedEffect(navigator) {
        UI.globalNavigator = navigator
    }

    GlobalEventChannel.filter(StateChangedEvent::class).OnDispose {
        var screenPending = false
        subscribeAlways { e ->
            if (e.state == State.Menu) {
                screenPending = false
                var index = navigator.backStack.indexOfFirst { it is GameScreen }
                val prev = navigator.backStack.getOrNull(index - 1)
                if (prev is BattleRoomScreen) index -= 1
                if (index != -1) navigator.backStack.removeRange(index, navigator.backStack.size)
            } else {
                val current = navigator.current
                if (current !is GameScreen && !screenPending) {
                    screenPending = true
                    navigator.push<GameScreen>()
                }
            }
        }
    }

    BackHandler(navigator.canPop) { navigator.pop() }

    RWPPTheme {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides RWSelectionColors,
                LocalWindowManager provides constraintWindowManager(maxWidth, maxHeight)
            ) {
                navigator.Content()

                var warningDialogVisible by remember { mutableStateOf(false) }

                LaunchedEffect(UI.warning) {
                    if (UI.warning != null) {
                        warningDialogVisible = true
                    }
                }

                AnimatedAlertDialog(
                    warningDialogVisible,
                    isDanger = true,
                    onDismissRequest = {
                        warningDialogVisible = false
                        UI.warning = null
                    }
                ) { dismiss ->
                    AeroPanel(
                        modifier = Modifier
                            .size(500.dp)
                            .autoClearFocus(),
                        borderColor = MaterialTheme.colorScheme.error
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                AeroHeaderWithCloseButton(
                                    title = "SYSTEM ALERT",
                                    tag = "WARN.SYS",
                                    color = MaterialTheme.colorScheme.error,
                                    onDismissRequest = dismiss
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                AeroDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AeroSciFiWarningIndicator(
                                    size = 120.dp,
                                    accentColor = MaterialTheme.colorScheme.error
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "/// HAZARD LEVEL: CRITICAL ///",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 2.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                            shape = AeroSciFiTheme.ChamferAeroShape(8f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                                            shape = AeroSciFiTheme.ChamferAeroShape(8f)
                                        )
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = UI.warning?.reason?.ifEmpty { "UNKNOWN SYSTEM EXCEPTION DETECTED" } ?: "",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            AeroButton(
                                text = "ACKNOWLEDGE",
                                isDanger = true,
                                clickSound = Sounds.closePopup,
                                onClick = dismiss
                            )
                        }
                    }
                }


                var questionDialogVisible by remember { mutableStateOf(false) }
                LaunchedEffect(UI.question) {
                    questionDialogVisible = UI.question != null
                }

                AnimatedAlertDialog(
                    questionDialogVisible,
                    onDismissRequest = {
                        questionDialogVisible = false
                        UI.question?.callback?.invoke(null)
                        navigator.pop()
                        UI.question = null
                    }
                ) { dismiss ->
                    val widthFraction = if (LocalWindowManager.current == WindowManager.Small) 0.9f else 0.75f

                    AeroPanel(
                        modifier = Modifier
                            .fillMaxWidth(widthFraction)
                            .autoClearFocus()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            AeroHeaderWithCloseButton(
                                title = UI.question?.title ?: "SYSTEM QUERY",
                                tag = "INQUIRY.REQ",
                                color = MaterialTheme.colorScheme.primary,
                                onDismissRequest = {
                                    dismiss()
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            AeroDivider()
                            Spacer(modifier = Modifier.height(12.dp))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = UI.question?.message ?: "",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                var message by remember { mutableStateOf("") }
                                val submitReply = {
                                    if (message.isNotEmpty()) {
                                        UI.question?.callback?.invoke(message)
                                        message = ""
                                        questionDialogVisible = false
                                    }
                                }

                                AeroTextField(
                                    value = message,
                                    onValueChange = { message = it },
                                    placeholder = "REPLY DATA",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onKeyEvent {
                                            if (it.key == Key.Enter) {
                                                submitReply()
                                                true
                                            } else false
                                        },
                                    trailingIcon = {
                                        AeroIconButton(
                                            painter = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowForward),
                                            size = 28.dp,
                                            iconSize = 16.dp,
                                            onClick = { submitReply() }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                var reloadingModViewVisible by remember { mutableStateOf(false) }
                GlobalEventChannel.filter(ReloadModEvent::class).OnDispose {
                    subscribeAlways {
                        reloadingModViewVisible = true
                    }
                }

                GlobalEventChannel.filter(ReloadModFinishedEvent::class).OnDispose {
                    subscribeAlways {
                        reloadingModViewVisible = false
                    }
                }

                AeroLoadingView(reloadingModViewVisible, onLoaded = {}) { null }

                var currentDialog by remember {
                    mutableStateOf<(@Composable (dismiss: () -> Unit) -> Unit)?>(null)
                }
                AnimatedAlertDialog(
                    currentDialog != null,
                    {
                        currentDialog = null
                    }
                ) { dismiss ->
                    LaunchedEffect(UI.dialogContent) {
                        if (UI.dialogContent == null) {
                            dismiss()
                            delay(DIALOG_BUILD_TIME.milliseconds)
                            currentDialog = null
                        }

                        currentDialog = UI.dialogContent
                    }

                    currentDialog?.invoke(dismiss)
                }

                MapInspectorDialog(
                    visible = UI.inspectingMap != null,
                    map = UI.inspectingMap,
                    onDismissRequest = { UI.inspectingMap = null }
                )
            }
        }
    }
}