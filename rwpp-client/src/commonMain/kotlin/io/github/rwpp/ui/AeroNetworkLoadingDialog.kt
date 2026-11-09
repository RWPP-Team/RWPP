/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.game.NetworkGameLoadingState
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.api.util.Reflect
import io.github.rwpp.widget.v2.*

internal data class NativeNetworkLoadingSnapshot(
    val loadingMap: Boolean,
    val receivedBytes: Int = 0,
    val totalBytes: Int = 0,
)

/** Reads native volatile packet counters without waiting for the game-frame dispatcher. */
internal fun readNativeNetworkLoadingSnapshot(): NativeNetworkLoadingSnapshot? = runCatching {
    if (NetworkGameLoadingState.isLoadingMap) {
        return NativeNetworkLoadingSnapshot(loadingMap = true)
    }

    val network = GameEngine.getInstance().networkEngine
    if (!network.networked || network.isServer || network.gameStarted) return null
    val connection = network.clientConnection ?: return null
    val total = (Reflect.get<Number>(connection, "receivingProgressTotal")?.toInt() ?: 0)
    if (total <= MINIMUM_VISIBLE_PACKET_BYTES) return null
    val received = (Reflect.get<Number>(connection, "receivingProgressBytes")?.toInt() ?: 0)
    NativeNetworkLoadingSnapshot(
        loadingMap = false,
        receivedBytes = received.coerceIn(0, total),
        totalBytes = total,
    )
}.getOrNull()

private const val MINIMUM_VISIBLE_PACKET_BYTES = 20_000

@Composable
internal fun AeroNetworkLoadingDialog(snapshot: NativeNetworkLoadingSnapshot?) {
    AnimatedAlertDialog(
        visible = snapshot != null,
        onDismissRequest = {},
        enableDismiss = false,
    ) {
        val current = snapshot ?: NativeNetworkLoadingSnapshot(loadingMap = true)
        AeroPanel(
            modifier = Modifier
                .width(460.dp)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AeroHeader(
                    title = "NETWORK STARTUP",
                    tag = if (current.loadingMap) "MAP.LOAD" else "NET.RECV",
                    modifier = Modifier.fillMaxWidth(),
                )
                AeroDivider()
                AeroSciFiLoader(
                    size = 86.dp,
                    accentColor = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = readI18n(
                        if (current.loadingMap) "rwpp.multiplayer.join.loadingMap"
                        else "rwpp.multiplayer.join.receivingNetworkData",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                )

                if (!current.loadingMap && current.totalBytes > 0) {
                    val fraction = (current.receivedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        text = "${formatNetworkBytes(current.receivedBytes)} / ${formatNetworkBytes(current.totalBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                } else if (current.loadingMap) {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun formatNetworkBytes(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000f)
    else -> "$bytes B"
}
