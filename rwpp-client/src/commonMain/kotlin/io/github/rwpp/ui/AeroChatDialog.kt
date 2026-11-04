/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.game.Game
import io.github.rwpp.api.game.sendChatMessageOrCommand
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.widget.v2.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal fun outgoingHudChat(text: String, team: Boolean): String? = text.trim().takeIf(String::isNotEmpty)
    ?.let { if (team) "-t $it" else it }

@Composable
internal fun AeroChatDialog(onDismiss: () -> Unit) {
    val game = koinInject<Game>()
    fun close() {
        onDismiss()
    }
    AeroChatPane(UI.chatMessages, isSendingTeamChat, { isSendingTeamChat = it }, { text, team ->
        val outgoing = outgoingHudChat(text, team) ?: return@AeroChatPane
        GameScope.launch { game.gameRoom.sendChatMessageOrCommand(outgoing) }
        close()
    }, ::close, animated = false)
}

@Composable
internal fun AeroChatPane(
    history: AnnotatedString, team: Boolean, onTeamChange: (Boolean) -> Unit,
    onSend: (String, Boolean) -> Unit, onClose: () -> Unit, animated: Boolean = true,
    translate: (String) -> String = { readI18n(it) }
) {
    val intro = remember { androidx.compose.animation.core.Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, androidx.compose.animation.core.tween(if (animated) 180 else 0)) }
    var message by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val backdropInteractionSource = remember { MutableInteractionSource() }
    val panelInteractionSource = remember { MutableInteractionSource() }
    fun send() {
        if (outgoingHudChat(message, team) != null) onSend(message, team)
    }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    LaunchedEffect(history) { scroll.scrollTo(0) }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = backdropInteractionSource, indication = null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        AeroPanel(
            Modifier.widthIn(max = 960.dp).fillMaxWidth(0.94f).heightIn(max = 720.dp)
                .fillMaxHeight(0.85f)
                .graphicsLayer { alpha = intro.value; scaleX = 0.97f + intro.value * 0.03f; scaleY = scaleX }
                .clickable(interactionSource = panelInteractionSource, indication = null) { }
                .onPreviewKeyEvent {
                    it.type == KeyEventType.KeyDown && when (it.key) {
                        Key.Escape -> {
                            onClose(); true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            send(); true
                        }

                        else -> false
                    }
                }) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AeroHeader(title = if (team) "TEAM CHAT" else "CHAT", tag = "CHAT")
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    BasicText(
                        history, Modifier.fillMaxWidth(),
                        style = TextStyle(color = Color.White, fontSize = 13.sp)
                    )
                }
                AeroTextField(
                    message,
                    { message = it },
                    translate("rwpp.ingame.messagePlaceholder"),
                    Modifier.fillMaxWidth(),
                    focusRequester = focus,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroToggleTile(translate("rwpp.ingame.teamOnly"), team, onTeamChange, Modifier.weight(1f))
                    AeroButton(translate("rwpp.common.send"), { send() }, Modifier.width(100.dp))
                    AeroButton(translate("rwpp.common.close"), { onClose() }, Modifier.width(100.dp))
                }
            }
        }
    }
}
