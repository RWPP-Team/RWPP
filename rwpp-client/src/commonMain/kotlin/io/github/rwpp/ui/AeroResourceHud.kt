/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.render.BitmapOrTexture
import io.github.rwpp.game.hud.AeroResourceHudLine
import io.github.rwpp.game.hud.AeroResourceHudSegment
import io.github.rwpp.graphics.hudImageFromPixels
import io.github.rwpp.graphics.prepareHudTexture
import io.github.rwpp.graphics.readHudTexturePixels

/**
 * Mod resource rows belong to the status readout. They can sit above or below the credit line and
 * either stack vertically or share one horizontal line. A chained group never wraps internally.
 */
@Composable
internal fun AeroCustomResourceHud(
    lines: List<AeroResourceHudLine>,
    horizontal: Boolean = false,
) {
    if (lines.isEmpty()) return
    if (horizontal) {
        // A plain Row never wraps; chained groups stay on the line the player configured.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lines.forEach { line -> AeroCustomResourceRow(line) }
        }
    } else {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            lines.forEach { line -> AeroCustomResourceRow(line) }
        }
    }
}

@Composable
private fun AeroCustomResourceRow(line: AeroResourceHudLine) {
    val segments = line.segments.ifEmpty {
        listOf(AeroResourceHudSegment(line.text, line.displayColor))
    }
    Row(
        modifier = Modifier.widthIn(max = 420.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = hudPanelAlpha(0.18f))),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEach { segment -> AeroCustomResourceSegment(line, segment) }
    }
}

@Composable
private fun AeroCustomResourceSegment(line: AeroResourceHudLine, segment: AeroResourceHudSegment) {
    // The native sidebar paints getIconImage() beside the value. Mods commonly blank
    // displayPrefixInHUD, in which case the icon is the only label the segment has.
    segment.icon?.let { texture ->
        rememberAeroResourceIcon(texture)?.let { icon ->
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
    Text(
        text = segment.text,
        color = if (line.secondaryTeam) {
            MaterialTheme.colorScheme.onSurface
        } else {
            segment.displayColor?.let(::Color) ?: MaterialTheme.colorScheme.primary
        },
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
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

/** Reads the owned CPU pixel copy of a native texture; a broken asset never hides the row. */
@Composable
private fun rememberAeroResourceIcon(texture: BitmapOrTexture): ImageBitmap? = remember(
    texture, texture.id, texture.version,
) {
    runCatching {
        val width = texture.width.coerceAtLeast(1)
        val height = texture.height.coerceAtLeast(1)
        prepareHudTexture(texture)
        hudImageFromPixels(
            readHudTexturePixels(texture, 0, 0, width, height, width, height),
            width,
            height,
        )
    }.getOrNull()
}
