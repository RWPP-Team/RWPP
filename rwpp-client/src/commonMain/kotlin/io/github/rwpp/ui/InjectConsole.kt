/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.widget.v2.*
import io.github.rwpp.api.logging.logger

internal var logStr = mutableStateOf(AnnotatedString(""))

@Composable
fun InjectConsole(
    modifier: Modifier = Modifier
) {
    val message by remember { logStr }
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(message) {
        if (autoScroll && scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val lineCount = remember(message) {
        if (message.isEmpty()) 0 else message.text.count { it == '\n' }
    }

    AeroPanel(
        modifier = modifier.fillMaxSize(0.85f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 1. 顶部标题栏与快捷工具栏 (Header & Controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AeroHeader(
                    title = "ASM MIXIN CONSOLE",
                    tag = "MIXIN.TERMINAL",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.MoreVert),
                        size = 32.dp,
                        iconSize = 16.dp,
                        forceHover = autoScroll,
                        accentColor = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        onClick = { autoScroll = !autoScroll }
                    )

                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Share),
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = {
                            if (message.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(message.text))
                            }
                        }
                    )

                    AeroIconButton(
                        painter = rememberVectorPainter(Icons.Default.Delete),
                        size = 32.dp,
                        iconSize = 16.dp,
                        isDanger = true,
                        onClick = { logStr.value = AnnotatedString("") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            AeroDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))

            val terminalShape = remember { AeroSciFiTheme.ChamferAeroShape(8f) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(terminalShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), terminalShape)
                    .padding(12.dp)
            ) {
                if (message.isEmpty()) {
                    Text(
                        text = "> MIXIN REGISTRATION READY. WAITING FOR HOST GAME BOOT...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ LOG LINES: $lineCount ]",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (autoScroll) "[ AUTO-SCROLL: ON ]" else "[ AUTO-SCROLL: LOCK ]",
                        color = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = "STATUS: ONLINE",
                    color = Color(0xFF00FF66).copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private val TraceColor = Color(0xFF8A99AD)
private val InfoColor = Color(0xFF00FF66)
private val WarnColor = Color(0xFFFFD700)
private val ErrorColor = Color(0xFFFF4444)

interface MixinConsoleLogger {
    fun logging(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
    fun exception(e: Throwable)
}

val defaultMixinLogger: MixinConsoleLogger = object : MixinConsoleLogger {
    override fun logging(message: String) {
        logger().trace(message)
        logStr.value += buildAnnotatedString {
            withStyle(style = SpanStyle(color = TraceColor, fontFamily = FontFamily.Monospace)) {
                append("[L] $message")
            }
            append("\n")
        }
    }

    override fun info(message: String) {
        logger().info(message)
        logStr.value += buildAnnotatedString {
            withStyle(style = SpanStyle(color = InfoColor, fontFamily = FontFamily.Monospace)) {
                append("[I] $message")
            }
            append("\n")
        }
    }

    override fun warn(message: String) {
        logger().warn(message)
        logStr.value += buildAnnotatedString {
            withStyle(style = SpanStyle(color = WarnColor, fontFamily = FontFamily.Monospace)) {
                append("[W] $message")
            }
            append("\n")
        }
    }

    override fun error(message: String) {
        logger().error(message)
        logStr.value += buildAnnotatedString {
            withStyle(style = SpanStyle(color = ErrorColor, fontFamily = FontFamily.Monospace)) {
                append("[E] $message")
            }
            append("\n")
        }
    }

    override fun exception(e: Throwable) {
        logger().error(e.message, e)
        logStr.value += buildAnnotatedString {
            withStyle(style = SpanStyle(color = ErrorColor, fontFamily = FontFamily.Monospace)) {
                append("[E] EXCEPTION: ${e.message.toString()}\n")
                append(e.stackTraceToString())
            }
            append("\n")
        }
    }
}