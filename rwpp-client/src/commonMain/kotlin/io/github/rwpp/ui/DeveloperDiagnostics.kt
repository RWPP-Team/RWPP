/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rwpp.api.i18n.readI18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun DeveloperPerformanceOverlay(enabled: Boolean) {
    if (!enabled) return
    var sample by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (isActive) {
            sample = withContext(Dispatchers.Default) {
                val runtime = Runtime.getRuntime()
                val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val max = runtime.maxMemory() / (1024 * 1024)
                val stacks = runCatching { Thread.getAllStackTraces() }.getOrNull()
                val threads = stacks?.size ?: Thread.activeCount()
                val frames = stacks?.values?.sumOf { it.size }
                "${readI18n("rwpp.debug.heap")}: $used / $max MiB\n" +
                        "${readI18n("rwpp.debug.threads")}: $threads" +
                        (frames?.let { "  ·  ${readI18n("rwpp.debug.stackFrames")}: $it" } ?: "")
            }
            delay(2000.milliseconds)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            sample, Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp),
            color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace
        )
    }
}
