/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.corrodinggames.rts.game.units.actions.ActionType
import com.corrodinggames.rts.game.units.actions.MapPingType
import com.corrodinggames.rts.game.units.actions.PingMapAction
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.api.i18n.readI18n
import io.github.rwpp.sound.Sounds
import io.github.rwpp.widget.v2.AeroButton
import io.github.rwpp.widget.v2.AeroHeader
import io.github.rwpp.widget.v2.AeroPanel
import kotlinx.coroutines.launch

/** Native touch action bars show a persistent queue panel before issuing a queue command. */
internal fun hudUsesTouchQueuePanel(
    touch: Boolean,
    type: ActionType,
    persistent: Boolean,
    queueKnown: Boolean = true
): Boolean =
    touch && (type == ActionType.popupQueue || type == ActionType.none && persistent && queueKnown)

internal fun activateHudMapPing() = activateHudMapPing(MapPingType.normal)

internal fun activateHudMapPing(type: MapPingType) {
    GameScope.launch {
        val engine = GameEngine.getInstance()
        if (!engine.replayEngine.isPlayingReplay) {
            engine.interfaceEngine.startMapPingAction()
            engine.interfaceEngine.activeSpecialAction = PingMapAction(type)
        }
    }
}

@Composable
internal fun HudMapPingPicker(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AeroPanel(modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AeroHeader("PING MAP", tag = "MAP SIGNAL")
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (type in MapPingType.entries) {
                    AeroButton(
                        type.displayName,
                        onClick = { activateHudMapPing(type); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        clickSound = Sounds.select
                    )
                }
            }
            AeroButton(readI18n("rwpp.common.cancel"), onClick = onDismiss, clickSound = Sounds.cancel)
        }
    }
}

/** First tap selects production; every later tap builds until selection is dismissed/changed. */
internal class HudProductionSelection {
    var identity by mutableStateOf<String?>(null)
        private set

    fun tap(next: String): Boolean {
        val repeat = identity == next
        identity = next
        return repeat
    }

    fun clear() {
        identity = null
    }
}

/** Choose a free side of the console rather than overlaying its action buttons. */
internal fun queuePopupPosition(anchor: IntRect, window: IntSize, popup: IntSize, gap: Int): IntOffset {
    val candidates = listOf(
        IntOffset(anchor.left - popup.width - gap, anchor.top),
        IntOffset(anchor.right + gap, anchor.top),
        IntOffset(anchor.left, anchor.top - popup.height - gap),
        IntOffset(anchor.left, anchor.bottom + gap),
    )
    return candidates.firstOrNull { it.x >= 0 && it.y >= 0 && it.x + popup.width <= window.width && it.y + popup.height <= window.height }
        ?: IntOffset(
            (anchor.left - popup.width - gap).coerceIn(0, (window.width - popup.width).coerceAtLeast(0)),
            anchor.top.coerceIn(0, (window.height - popup.height).coerceAtLeast(0))
        )
}

@Composable
internal fun TouchQueuePanel(
    button: CommandButton,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    AeroPanel(modifier.fillMaxWidth().padding(vertical = 4.dp).pointerInput(Unit) { detectTapGestures {} }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AeroHeader(button.label.substringBefore('\n'), tag = "PRODUCTION")
            Text(readI18n("rwpp.hud.queue.count", button.queued), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AeroButton(
                    readI18n("rwpp.hud.queue.add"), onClick = onAdd, enabled = button.canAdd,
                    modifier = Modifier.weight(1f), clickSound = Sounds.select
                )
                AeroButton(
                    readI18n("rwpp.hud.queue.remove"), onClick = onRemove, enabled = button.queued > 0,
                    modifier = Modifier.weight(1f), clickSound = Sounds.cancel, isDanger = true
                )
            }
        }
    }
}

/** Observes touches without consuming them or adding a full-screen Compose hit target. */
internal object HudQueuePopupTouch {
    var active = false
    var popup = Rect.Zero
    var selectedIdentity: String? = null
    var actionAt: ((Offset) -> String?)? = null
    var onDismiss: (() -> Unit)? = null
    fun down(point: Offset) {
        if (active && queueDismissesForPoint(point, popup, selectedIdentity, actionAt?.invoke(point))) dismiss()
    }

    fun dismiss() {
        if (active) {
            active = false
            onDismiss?.invoke()
        }
    }
}

internal fun queueDismissesForPoint(point: Offset, popup: Rect, selected: String?, hit: String?): Boolean =
    !popup.contains(point) && (selected == null || selected != hit)

@Composable
internal fun TouchQueuePopup(button: CommandButton?, onAdd: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    var retained by remember { mutableStateOf<CommandButton?>(null) }
    if (button != null) SideEffect { retained = button }
    SideEffect {
        HudQueuePopupTouch.active = button != null
        if (button == null) HudQueuePopupTouch.popup = Rect.Zero
        HudQueuePopupTouch.selectedIdentity = button?.identity
        HudQueuePopupTouch.onDismiss = onClose
    }
    DisposableEffect(Unit) {
        onDispose {
            HudQueuePopupTouch.active = false
            HudQueuePopupTouch.popup = Rect.Zero
            HudQueuePopupTouch.selectedIdentity = null
            HudQueuePopupTouch.onDismiss = null
        }
    }
    val animated =
        runCatching { io.github.rwpp.appKoin.get<io.github.rwpp.config.Settings>().hudAnimations }.getOrDefault(true)
    // Match the construction tooltip: short fade + eight-dp vertical translation, no Aero scan/glitch.
    val alpha by animateFloatAsState(if (button != null) 1f else 0f, tween(if (animated) 180 else 0))
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize
            ): IntOffset {
                val point = queuePopupPosition(anchorBounds, windowSize, popupContentSize, gap)
                HudQueuePopupTouch.popup = Rect(
                    point.x.toFloat(), point.y.toFloat(),
                    (point.x + popupContentSize.width).toFloat(), (point.y + popupContentSize.height).toFloat()
                )
                return point
            }
        }
    }
    if (button != null || alpha > 0f) Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        (button ?: retained)?.let { shown ->
            TouchQueuePanel(
                shown, onAdd = { if (button != null) onAdd() },
                onRemove = { if (button != null) onRemove() },
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha; translationY = (1f - alpha) * -8.dp.toPx() })
        }
    }
}
