/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.ui

import androidx.compose.runtime.*
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.game.sandbox.SandboxUnitDataSnapshot
import io.github.rwpp.game.sandbox.requestSandboxUnitDataSnapshot
import io.github.rwpp.game.sandbox.sandboxUnitDataUiAllowed
import com.corrodinggames.rts.game.units.Unit as GameUnit

/**
 * Sandbox-only controls live in their own HUD component instead of being pinned inside the
 * command console. The component is registered only for the sandbox mode, so the HUD editor list
 * does not offer it in normal battles.
 */
@Composable
internal fun GameScreen.SandboxEditorHud() {
    val source = remember { SandboxEditorSnapshotSource() }
    var snapshot by remember { mutableStateOf<SandboxUnitDataSnapshot?>(null) }
    OnGameUpdate {
        val next = source.update(GameEngine.getInstance(), selectedUnits)
        if (next !== snapshot) snapshot = next
    }
    snapshot?.let { SandboxUnitEditorControls(it) }
}

/**
 * Mirrors the sampling the command console used to run: throttled, cancelled on selection change,
 * and only active for editable units in a local sandbox. The snapshot stays a plain field because
 * update and callback both run on the game thread; the HUD publishes it as Compose state.
 */
private class SandboxEditorSnapshotSource {
    private var selectionIds: List<Long> = emptyList()
    private var generation = 0
    private var lastSampleAt = Long.MIN_VALUE
    private var pending = false
    private var snapshot: SandboxUnitDataSnapshot? = null

    fun update(engine: GameEngine, selection: List<GameUnit>): SandboxUnitDataSnapshot? {
        val editable = selection.isNotEmpty() &&
                selection.none { it is com.corrodinggames.rts.game.units.EditorUnit }
        if (!editable || !sandboxUnitDataUiAllowed(engine)) {
            reset()
            return null
        }
        val ids = selection.map { it.id }.distinct()
        if (ids != selectionIds) {
            selectionIds = ids
            generation++
            pending = false
            snapshot = null
        }
        val now = System.nanoTime()
        if (!pending && (snapshot == null || now - lastSampleAt >= SAMPLE_INTERVAL_NANOS)) {
            lastSampleAt = now
            pending = true
            val requestGeneration = generation
            val requestIds = selectionIds
            requestSandboxUnitDataSnapshot(requestIds) { next ->
                if (requestGeneration == generation && requestIds == selectionIds) {
                    pending = false
                    if (snapshot != next) snapshot = next
                }
            }
        }
        return snapshot
    }

    private fun reset() {
        if (selectionIds.isNotEmpty()) generation++
        selectionIds = emptyList()
        lastSampleAt = Long.MIN_VALUE
        pending = false
        snapshot = null
    }

    private companion object {
        const val SAMPLE_INTERVAL_NANOS = 200_000_000L
    }
}
