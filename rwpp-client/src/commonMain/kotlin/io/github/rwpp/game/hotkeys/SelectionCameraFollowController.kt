/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.game.hotkeys

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwpp.api.coroutines.GameScope
import io.github.rwpp.logic.selection.SelectionFollowSession
import kotlinx.coroutines.launch
import com.corrodinggames.rts.game.units.Unit as GameUnit

/** Read-only state exposed for Android HUD rendering and native runtime probes. */
data class SelectionCameraFollowStatus(
    val active: Boolean = false,
    val capturedUnitIds: List<Long> = emptyList(),
    val anchorUnitId: Long? = null,
    val anchorTypeId: String? = null,
    val rolloverCount: Int = 0,
    /** Stable diagnostic token describing the current follow lifecycle. */
    val reason: String = "disabled",
)

/** Selection-group camera lock using the native camera positioning API; it never changes unit state. */
object SelectionCameraFollowController {
    @Volatile
    private var publishedStatus = SelectionCameraFollowStatus()
    private var session: SelectionFollowSession<GameUnit>? = null
    private var rollovers = 0
    private var capturedEngine: GameEngine? = null
    private var capturedMap: Any? = null
    private var lastSimulationTick = 0

    /** Probeable snapshot with native object IDs and current unit type. */
    val status: SelectionCameraFollowStatus get() = publishedStatus
    val isActive: Boolean get() = publishedStatus.active
    val capturedUnitIds: List<Long> get() = publishedStatus.capturedUnitIds
    val anchorUnitId: Long? get() = publishedStatus.anchorUnitId
    val anchorTypeId: String? get() = publishedStatus.anchorTypeId
    val rolloverCount: Int get() = publishedStatus.rolloverCount

    /** Toggle from UI/probe code. Selection capture and camera changes run on GameMainDispatcher. */
    fun toggle() {
        GameScope.launch { toggleOnGameThread(GameEngine.getInstance()) }
    }

    /** Explicitly releases the locked group from UI/probe code. */
    fun stop() {
        GameScope.launch { stopOnGameThread() }
    }

    internal fun toggleOnGameThread(engine: GameEngine) {
        if (session != null) {
            stopOnGameThread()
            return
        }
        val captured = engine.interfaceEngine.selectedUnits.filterIsInstance<GameUnit>().toList()
        val next = SelectionFollowSession(captured)
        val anchor = next.refreshAnchor(::isAliveFollowTarget)
        if (anchor == null) {
            session = null
            rollovers = 0
            publishedStatus = SelectionCameraFollowStatus(
                capturedUnitIds = captured.map { it.id },
                reason = "no_living_selection",
            )
            return
        }
        session = next
        capturedEngine = engine
        capturedMap = engine.map
        lastSimulationTick = engine.simulationTick
        rollovers = 0
        publish(next, anchor, active = true, reason = "following")
    }

    /** Called alongside native hotkey polling on the game dispatcher. */
    internal fun updateOnGameThread(engine: GameEngine, blocked: Boolean) {
        val captured = session ?: return
        if (capturedEngine !== engine || capturedMap !== engine.map || engine.simulationTick < lastSimulationTick) {
            stopOnGameThread("session_changed")
            return
        }
        if (blocked) return
        lastSimulationTick = engine.simulationTick
        val oldAnchor = captured.currentAnchor
        val anchor = captured.refreshAnchor(::isAliveFollowTarget)
        if (anchor == null) {
            val capturedIds = captured.capturedUnits.map { it.id }
            val finalRollovers = rollovers
            session = null
            capturedEngine = null
            capturedMap = null
            lastSimulationTick = 0
            rollovers = 0
            publishedStatus = SelectionCameraFollowStatus(
                capturedUnitIds = capturedIds,
                rolloverCount = finalRollovers,
                reason = "no_living_members",
            )
            return
        }

        val changed = oldAnchor !== anchor
        if (changed && oldAnchor != null) rollovers++
        if (changed) publish(
            captured,
            anchor,
            active = true,
            reason = if (oldAnchor == null) "following" else "anchor_changed"
        )

        // Native cinematic easing intentionally stops short and moves too slowly to lock a fast
        // unit. Center through the supported API each frame, then retain native map bounds.
        engine.centerCameraOn(anchor.x, anchor.y)
        engine.clampCamera()
    }

    private fun stopOnGameThread(reason: String = "disabled") {
        session = null
        capturedEngine = null
        capturedMap = null
        lastSimulationTick = 0
        rollovers = 0
        publishedStatus = SelectionCameraFollowStatus(reason = reason)
    }

    private fun isAliveFollowTarget(unit: GameUnit): Boolean =
        !unit.dead && !unit.removed && unit.transportedBy == null

    private fun publish(
        group: SelectionFollowSession<GameUnit>,
        anchor: GameUnit,
        active: Boolean,
        reason: String,
    ) {
        publishedStatus = SelectionCameraFollowStatus(
            active = active,
            capturedUnitIds = group.capturedUnits.map { it.id },
            anchorUnitId = anchor.id,
            anchorTypeId = runCatching { anchor.unitTypeInterface.internalName }.getOrNull(),
            rolloverCount = rollovers,
            reason = reason,
        )
    }
}
