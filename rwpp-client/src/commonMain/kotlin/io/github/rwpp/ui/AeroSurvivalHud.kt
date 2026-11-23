/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.game.hud.SurvivalWaveHud
import io.github.rwpp.game.hud.SurvivalSpawnStatus
import io.github.rwpp.widget.v2.AeroHeader
import io.github.rwpp.widget.v2.AeroPanel

internal data class SurvivalScreenMarker(val fraction: Offset, val status: SurvivalSpawnStatus)

@Composable
internal fun AeroSurvivalHud(wave: SurvivalWaveHud?, editing: Boolean) {
    if (wave == null && !editing) return
    AeroPanel {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AeroHeader("SURVIVAL")
            if (wave != null) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (wave.hasNext && wave.completed > 0) {
                        Text(readI18n("rwpp.survival.current", wave.completed), fontSize = 11.sp)
                        wave.currentWaveSummary?.let { Text(it, fontSize = 11.sp) }
                    }
                    SurvivalWaveDetails(wave)
                }
            }
        }
    }
}

@Composable
internal fun AeroSurvivalSpawns(markers: List<SurvivalScreenMarker>, details: SurvivalWaveHud?, textScale: Float) {
    if (markers.isEmpty()) return
    val color = MaterialTheme.colorScheme.error
    val scale = textScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val cardWidth = minOf(220.dp, viewportWidth)
        val cardScale = if (cardWidth > 0.dp) scale.coerceAtMost(viewportWidth / cardWidth) else 1f
        val scaledCardWidth = cardWidth * cardScale
        for ((fraction, status) in markers) {
            val x = viewportWidth * fraction.x
            val y = viewportHeight * fraction.y
            val label = readI18n(when (status) {
                SurvivalSpawnStatus.CANDIDATE -> "rwpp.survival.possibleSpawn"
                SurvivalSpawnStatus.PREDICTED -> "rwpp.survival.predictedSpawn"
                SurvivalSpawnStatus.CONFIRMED -> "rwpp.survival.spawn"
            })
            val pinpointed = status != SurvivalSpawnStatus.CANDIDATE
            Canvas(Modifier.absoluteOffset(x - 10.dp, y - 10.dp).size(20.dp)) {
                drawCircle(color.copy(alpha = if (pinpointed) 0.30f else 0.15f))
                drawCircle(color, radius = 8.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                if (pinpointed) drawCircle(color, radius = 2.5.dp.toPx())
            }
            if (details != null) {
                val left = (x - scaledCardWidth / 2).coerceIn(0.dp, (viewportWidth - scaledCardWidth).coerceAtLeast(0.dp))
                val top = (y + 12.dp).coerceIn(0.dp, (viewportHeight - 110.dp * cardScale).coerceAtLeast(0.dp))
                AeroPanel(
                    Modifier.absoluteOffset(left, top).width(cardWidth).graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = cardScale
                        scaleY = cardScale
                    },
                    borderColor = color,
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, fontSize = 11.sp, color = color)
                        SurvivalWaveDetails(details)
                    }
                }
            } else {
                Text(
                    label,
                    Modifier.absoluteOffset((x - 50.dp).coerceIn(0.dp, (viewportWidth - 120.dp).coerceAtLeast(0.dp)),
                        (y + 12.dp).coerceAtMost((viewportHeight - 20.dp * scale).coerceAtLeast(0.dp))
                    ).graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                    },
                    fontSize = 11.sp,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun SurvivalWaveDetails(wave: SurvivalWaveHud) {
    val status = when {
        wave.defeated -> readI18n("rwpp.survival.defeat", wave.completed)
        !wave.hasNext -> readI18n("rwpp.survival.completed", wave.completed)
        wave.seconds != null -> readI18n("rwpp.survival.countdown", wave.completed + 1, wave.seconds)
        else -> readI18n("rwpp.survival.next", wave.completed + 1)
    }
    Text(status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    wave.name?.let { Text(it, fontSize = 11.sp) }
    if (!wave.hasNext) return
    if (wave.units.isEmpty()) Text(readI18n("rwpp.survival.noUnits"), fontSize = 11.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((name, count, icon) in wave.units) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (icon != null) Image(icon, contentDescription = name, modifier = Modifier.size(25.dp))
                else Text(name, fontSize = 11.sp)
                Text("×$count", fontSize = 11.sp)
            }
        }
    }
}
