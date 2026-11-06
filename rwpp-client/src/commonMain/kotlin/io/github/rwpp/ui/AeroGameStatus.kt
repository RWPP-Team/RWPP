/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.config.Settings
import io.github.rwpp.game.hud.*
import io.github.rwpp.game.replay.aeroPresentationTeam
import io.github.rwpp.graphics.NativeWorldAccess
import org.koin.compose.koinInject

@Composable
internal fun GameScreen.AeroGameTelemetry() {
    val settings = koinInject<Settings>()
    var status by remember { mutableStateOf(AnnotatedString("")) }
    var resources by remember { mutableStateOf(emptyList<AeroResourceHudLine>()) }
    var lastTelemetrySampleAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val resourceSampler = remember { NativeAeroResourceHudSampler() }
    OnGameUpdate {
        val engine = GameEngine.getInstance()
        val now = System.currentTimeMillis()
        if (settings.hudCustomResources) {
            resourceSampler.sample(engine, now)?.let { resources = it }
        } else if (resources.isNotEmpty()) resources = emptyList()
        if (lastTelemetrySampleAt == Long.MIN_VALUE || now - lastTelemetrySampleAt >= 200L) {
            lastTelemetrySampleAt = now
            val team = aeroPresentationTeam(engine)
            if (team == null) {
                if (status.isNotEmpty()) status = AnnotatedString("")
            } else {
                val seconds = engine.gameTimeMillis / 1000
                val resource = com.corrodinggames.rts.game.units.custom.resources.CustomResource.CREDITS
                val income = team.getResourceAmount(resource)
                val expense = team.getNegativeResourceAmount(resource)
                val rate = "(${if (income >= 0) "+" else ""}$income)" + if (expense != 0) "($expense)" else ""
                val ping =
                    if (io.github.rwpp.appKoin.get<io.github.rwpp.api.game.Game>().state.multiplayer) team.ping else 0
                val clock = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                val fps = if (engine.settings.showFps) (engine as NativeWorldAccess).rwppFramesPerSecond() else 0
                val unitsCurrent = team.unitCaches.unitCapCount
                val unitsMax = team.unitsMax
                val nextStatus = buildAnnotatedString {
                    append("$${team.credits.toInt()} $rate\n")
                    if (engine.settings.showFps) {
                        append("$fps FPS  ·  ")
                        withStyle(SpanStyle(color = pingColor(ping))) { append(if (ping >= 0) "$ping ms" else "— ms") }
                        append("  ·  ")
                    }
                    append(clock)
                    append("  ·  ")
                    append("$unitsCurrent/$unitsMax")
                }
                if (nextStatus != status) status = nextStatus
            }
        }
    }
    val horizontal = settings.hudCustomResourcesLayout == "horizontal"
    val resourcesAbove = settings.hudCustomResourcesPosition == "above"
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.End,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (resourcesAbove) AeroCustomResourceHud(resources, horizontal)
        Text(
            status.takeIf { it.isNotEmpty() } ?: AnnotatedString(readI18n("rwpp.hud.telemetry.placeholder")),
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = hudPanelAlpha(0.18f)))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall.copy(
                shadow = androidx.compose.ui.graphics.Shadow(
                    Color.Black,
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                    3f
                )
            )
        )
        if (!resourcesAbove) AeroCustomResourceHud(resources, horizontal)
    }
}

internal fun hudPerformanceLabel(showFps: Boolean, fps: Int, ping: Int): String =
    if (showFps) "$fps FPS  ·  ${ping.coerceAtLeast(0)} ms  ·  " else ""

internal fun pingColor(ping: Int): Color = when {
    ping < 0 -> Color(0xffb0bec5)
    ping <= 100 -> Color(0xff72dc91)
    ping <= 400 -> Color(0xffffd166)
    else -> Color(0xffff6b6b)
}

@Composable
internal fun GameScreen.AeroGameMessages(events: Boolean) {
    val settings = koinInject<Settings>()
    var messages by remember { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    var lastSample by remember { mutableLongStateOf(0L) }
    val displayCache = remember { NativeHudMessageDisplayCache<AnnotatedString>() }
    val nativeSampler = remember { NativeHudMessageSampler() }
    OnGameUpdate {
        val now = System.currentTimeMillis()
        if (now - lastSample >= if (events) 50L else 16L) {
            lastSample = now
            val showTeam = !events && settings.hudChatShowTeam
            val showTimestamp = !events && settings.hudChatShowTimestamp
            val nextMessages = displayCache.update(
                rows = nativeSampler.sample(events, now, showTeam, showTimestamp),
                nextPresentationKey = (if (showTeam) 1 else 0) or (if (showTimestamp) 2 else 0),
                revealProgress = { line ->
                    nativeHudMessageRevealCharacterCount(line, showTeam, showTimestamp)
                },
            ) { line ->
                if (!line.isChatMessage) {
                    AnnotatedString(line.text)
                } else {
                    hudTypedText(
                        hudChatLine(
                            line.sender, line.text, line.senderColor, line.teamLetter, line.timestamp,
                            showTeam, showTimestamp, line.messageColor,
                        ),
                        line.ageMillis,
                        line.revealMillisPerCharacter,
                    )
                }
            }
            if (nextMessages !== messages) messages = nextMessages
        }
    }
    val motion = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(messages) {
        if (events && settings.hudAnimations && messages.isNotEmpty()) {
            motion.snapTo(0f); motion.animateTo(1f, androidx.compose.animation.core.tween(180))
        } else motion.snapTo(1f)
    }
    val visible = messages.isNotEmpty() || isEditMode
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f,
        androidx.compose.animation.core.tween(if (settings.hudAnimations) 180 else 0)
    )
    var retained by remember { mutableStateOf<List<AnnotatedString>>(emptyList()) }
    if (messages.isNotEmpty()) retained = messages
    val text = androidx.compose.ui.text.rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.onSurface
    val content = if (isEditMode && messages.isEmpty()) listOf(
        AnnotatedString(
            readI18n(if (events) "rwpp.hud.events.placeholder" else "rwpp.hud.chat.placeholder"),
        )
    ) else retained
    BoxWithConstraints(Modifier.widthIn(max = 330.dp)) {
        val width = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }
        val paragraph = text.measure(
            buildAnnotatedString { content.forEachIndexed { i, line -> if (i > 0) append("\n"); append(line) } },
            androidx.compose.ui.text.TextStyle(
                color = color, fontSize = 12.sp,
                shadow = androidx.compose.ui.graphics.Shadow(
                    Color.Black,
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                    3f
                )
            ),
            maxLines = 8, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width.coerceAtLeast(1))
        )
        val density = androidx.compose.ui.platform.LocalDensity.current
        val height = with(density) { paragraph.size.height.toDp() }
        val measuredWidth = with(density) { paragraph.size.width.toDp() }
        androidx.compose.foundation.Canvas(
            Modifier.width(measuredWidth)
                .height(if (visible || alpha > 0f) height else 0.dp).clipToBounds()
                .then(if (events && visible) Modifier.clickable { GameEngine.getInstance().interfaceEngine.warLogInterface.jumpToOldestEntry() } else Modifier)) {
            // Incoming chat changes its first line; do not bounce the entire history block.
            val shift = if (events) (1f - motion.value) * 6.dp.toPx() else 0f
            withTransform({ translate(0f, shift) }) {
                drawText(paragraph, alpha = alpha)
            }
        }
    }
}

/** Keep the native message color; optional metadata must not recolor its body. */
internal fun hudChatLine(
    name: String, message: String, color: Int, team: String?, timestamp: String,
    showTeam: Boolean, showTimestamp: Boolean, messageColor: Int? = null
): AnnotatedString = buildAnnotatedString {
    if (showTimestamp && timestamp.isNotBlank()) append("[$timestamp] ")
    if (showTeam && team != null) append("[$team] ")
    if (name.isNotBlank()) {
        withStyle(SpanStyle(color = Color(color), fontWeight = FontWeight.Bold)) { append(name) }
        append(": ")
    }
    if (messageColor == null) append(message)
    else withStyle(SpanStyle(color = Color(messageColor))) { append(message) }
}

/** Native reveal intervals apply to the complete rendered line, including optional metadata. */
internal fun hudTypedText(line: AnnotatedString, ageMillis: Long, msPerChar: Int): AnnotatedString =
    if (msPerChar <= 0) line else line.subSequence(
        0,
        (ageMillis.coerceAtLeast(0) / msPerChar).coerceAtMost(line.length.toLong()).toInt()
    )
