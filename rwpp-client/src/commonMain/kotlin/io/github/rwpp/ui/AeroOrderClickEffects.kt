/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.graphics.AeroOrderClick
import io.github.rwpp.game.graphics.AeroOrderClicks

internal fun DrawScope.drawAeroOrderPulse(point: Offset, age: Float, color: Color) {
    val progress = age.coerceIn(0f, 1f)
    val tint = color.copy(alpha = 1f - progress)
    drawCircle(tint, (4f + progress * 10f).dp.toPx(), point, style = Stroke(2.dp.toPx()))
    val arm = 3.dp.toPx()
    drawLine(tint, point - Offset(arm, 0f), point + Offset(arm, 0f), 1.dp.toPx())
    drawLine(tint, point - Offset(0f, arm), point + Offset(0f, arm), 1.dp.toPx())
}

private data class ClickFrame(
    val events: List<AeroOrderClick> = emptyList(), val now: Long = 0,
    val camera: Offset = Offset.Zero, val zoom: Float = 1f, val width: Float = 1f, val height: Float = 1f
)

@Composable
internal fun GameScreen.AeroOrderClickEffects() {
    val settings = appKoin.get<Settings>()
    var frame by remember { mutableStateOf(ClickFrame()) }
    val accent = MaterialTheme.colorScheme.primary
    val attack = MaterialTheme.colorScheme.error
    OnGameUpdate {
        if (!settings.aeroOrderClickEffects) {
            AeroOrderClicks.clear()
            if (frame.events.isNotEmpty()) frame = ClickFrame()
        } else {
            val now = System.currentTimeMillis()
            val events = AeroOrderClicks.frame(now)
            if (events.isNotEmpty() || frame.events.isNotEmpty()) {
                val engine = GameEngine.getInstance()
                frame = ClickFrame(
                    events, now, Offset(engine.cameraRenderX, engine.cameraRenderY), engine.zoomScale,
                    engine.screenWidth.coerceAtLeast(1f), engine.screenHeight.coerceAtLeast(1f)
                )
            }
        }
    }
    WorldCanvas(Modifier.fillMaxSize()) {
        frame.events.forEach {
            val point = Offset(
                (it.x - frame.camera.x) * frame.zoom * size.width / frame.width,
                (it.y - frame.camera.y) * frame.zoom * size.height / frame.height
            )
            drawAeroOrderPulse(point, (frame.now - it.time) / 700f, if (it.attack) attack else accent)
        }
    }
}

