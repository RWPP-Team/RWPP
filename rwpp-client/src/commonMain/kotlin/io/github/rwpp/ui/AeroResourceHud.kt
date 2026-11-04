/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.game.hud.AeroResourceHudLine

@Composable
internal fun AeroCustomResourceHud(lines: List<AeroResourceHudLine>) {
    if (lines.isEmpty()) return
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        lines.forEach { line ->
            Text(
                text = line.text,
                modifier = Modifier.widthIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.18f)),
                color = if (line.secondaryTeam) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    line.displayColor?.let(::Color) ?: MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        Color.Black,
                        androidx.compose.ui.geometry.Offset(1f, 1f),
                        3f,
                    ),
                ),
            )
        }
    }
}
