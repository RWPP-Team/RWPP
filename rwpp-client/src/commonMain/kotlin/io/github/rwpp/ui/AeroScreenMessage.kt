/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.config.Settings
import io.github.rwpp.game.nativeaccess.NativeScreenMessageAccess
import org.koin.compose.koinInject

/** Native transient messages last 5, 50 or 100 ticks; sampling faster than that is wasted work. */
internal const val SCREEN_MESSAGE_SAMPLE_MILLIS = 16L

private const val SCREEN_MESSAGE_FADE_IN_MILLIS = 140L
private const val SCREEN_MESSAGE_FADE_OUT_TICKS = 18f

/** One mirrored native screen message; ticks stay native so both sides agree on the countdown. */
internal data class NativeScreenMessageSnapshot(
    val text: String,
    val ageMillis: Long,
    val remainingTicks: Float,
)

internal fun screenMessageAlpha(ageMillis: Long, remainingTicks: Float, animated: Boolean): Float {
    if (!animated) return 1f
    val appear = (ageMillis.toFloat() / SCREEN_MESSAGE_FADE_IN_MILLIS).coerceIn(0f, 1f)
    val disappear = (remainingTicks / SCREEN_MESSAGE_FADE_OUT_TICKS).coerceIn(0f, 1f)
    return minOf(appear, disappear)
}

/**
 * Shows the message BattleInterface would have painted in the middle of the play field. The native
 * draw is suppressed while the original HUD is hidden, so the text is sampled from the same fields
 * and rendered by the Aero HUD instead.
 */
@Composable
internal fun GameScreen.AeroScreenMessage(visible: Boolean) {
    val settings = koinInject<Settings>()
    var message by remember { mutableStateOf<NativeScreenMessageSnapshot?>(null) }
    var lastSample by remember { mutableLongStateOf(0L) }

    OnGameUpdate {
        val now = System.currentTimeMillis()
        if (!visible) {
            if (message != null) message = null
            lastSample = now
            return@OnGameUpdate
        }
        val elapsed = now - lastSample
        if (elapsed < SCREEN_MESSAGE_SAMPLE_MILLIS) return@OnGameUpdate
        lastSample = now
        val access = GameEngine.getInstance().interfaceEngine.battleInterface as? NativeScreenMessageAccess
        val text = access?.rwppScreenMessage()
        val remaining = access?.rwppScreenMessageTimer() ?: 0f
        val current = message
        message = when {
            text.isNullOrEmpty() || remaining <= 0f -> null
            current != null && current.text == text ->
                current.copy(ageMillis = current.ageMillis + elapsed, remainingTicks = remaining)

            else -> NativeScreenMessageSnapshot(text, 0L, remaining)
        }
    }

    val snapshot = message
    if (!visible || snapshot == null) return

    val alpha = screenMessageAlpha(snapshot.ageMillis, snapshot.remainingTicks, settings.hudAnimations)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = snapshot.text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge.copy(
                shadow = Shadow(Color.Black.copy(alpha = 0.8f * alpha), Offset(0f, 2f), 8f)
            ),
            modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 32.dp),
        )
    }
}
