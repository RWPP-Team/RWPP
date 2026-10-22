/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.rwpp.App
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.sendChatMessageOrCommand
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.ui.UI.chatMessages
import io.github.rwpp.widget.*
import org.koin.compose.koinInject

/** State shared by desktop and Android UI roots. */
var isSendingTeamChat by mutableStateOf(false)
var isShowSendMessageDialog by mutableStateOf(false)

fun showSendMessageDialog() {
    isShowSendMessageDialog = true
}

/** Platform implementations draw the configured background image when it can be read. */
@Composable
internal expect fun PlatformBackgroundLayer(path: String)

/** Main RWPP screen and the in-game overlays, shared by both host surfaces. */
@Composable
fun MainComposeContent(showBackground: Boolean = true, safeInsets: Boolean = false) {
    val foregroundInsets =
        if (safeInsets) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
        } else {
            Modifier
        }
    Box(modifier = Modifier.fillMaxSize()) {
        if (showBackground) {
            PlatformBackgroundLayer(UI.backgroundImagePath)
        }
        Box(modifier = Modifier.fillMaxSize().then(foregroundInsets), contentAlignment = Alignment.Center) {
            if (isLoading) {
                MenuLoadingView(loadingMessage)
            } else {
                App()
            }
        }
    }

    if (isShowSendMessageDialog) {
        val game = koinInject<Game>()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(enabled = false) {},
        ) {
            Box(Modifier.fillMaxSize().then(foregroundInsets), contentAlignment = Alignment.Center) {
                RWPPTheme {
                    BorderCard(
                        modifier = Modifier
                            .size(550.dp, 540.dp)
                            .onKeyEvent {
                                if (it.key == Key.Escape && it.type == KeyDown) {
                                    isShowSendMessageDialog = false
                                    isSendingTeamChat = false
                                    true
                                } else false
                            },
                        backgroundColor = Color(53, 57, 53),
                        shape = RectangleShape
                    ) {
                    var chatMessage by remember { mutableStateOf("") }
                    var allChatMessages by remember(chatMessages) {
                        mutableStateOf(TextFieldValue(chatMessages))
                    }

                    Box {
                        fun onExit() {
                            isShowSendMessageDialog = false
                            isSendingTeamChat = false
                        }

                        fun onSendMessage() {
                            if (isSendingTeamChat) {
                                game.gameRoom.sendChatMessage("-t $chatMessage")
                            } else {
                                game.gameRoom.sendChatMessageOrCommand(chatMessage)
                            }
                            chatMessage = ""
                            onExit()
                        }

                        ExitButton { onExit() }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Spacer(modifier = Modifier.height(30.dp))
                            TextField(
                                value = allChatMessages,
                                onValueChange = { allChatMessages = it },
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                colors = RWTextFieldColors,
                                maxLines = 100
                            )
                            RWSingleOutlinedTextField(
                                label = if (isSendingTeamChat) readI18n("rwpp.ingame.sendTeamMessage") else readI18n("rwpp.ingame.sendMessage"),
                                value = chatMessage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                                    .onKeyEvent {
                                        if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && chatMessage.isNotEmpty()) {
                                            onSendMessage()
                                            true
                                        } else if (it.key == Key.Escape && it.type == KeyDown) {
                                            onExit()
                                            true
                                        } else false
                                    },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.clickable { onSendMessage() }
                                    )
                                },
                                onValueChange = { chatMessage = it },
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                RWTextButton(readI18n("rwpp.ingame.sendMessage"), modifier = Modifier.padding(5.dp)) {
                                    game.gameRoom.sendChatMessageOrCommand(chatMessage)
                                    chatMessage = ""
                                    onExit()
                                }
                                RWTextButton(readI18n("rwpp.ingame.sendTeamMessage"), modifier = Modifier.padding(5.dp)) {
                                    game.gameRoom.sendChatMessage("-t $chatMessage")
                                    chatMessage = ""
                                    onExit()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Desktop boot surface only; the running game owns its separate offscreen Compose scene. */
@Composable
fun LoadingComposeContent() {
    Box(Modifier.fillMaxSize()) {
        PlatformBackgroundLayer(UI.backgroundImagePath)
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MenuLoadingView(loadingMessage)
            }
        }
    }
}
